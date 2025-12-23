# Qwen3-VL – Jupyter Notebook Load & Performance Test

Este notebook tem como objetivo **simular uma aplicação real** consumindo um modelo **Qwen3-VL** deployado em um **Model Server do OpenShift AI**, realizando **inferências multimodais (imagem + texto)** sob diferentes níveis de concorrência, coletando métricas de **latência, throughput e tokens/s**.
O notebook valida a viabilidade do uso do Qwen3-VL em OpenShift AI para aplicações multimodais reais, com métricas confiáveis e comportamento previsível sob carga.

## Objetivos do Notebook

- Simular consumo real de um modelo multimodal
- Executar inferências concorrentes
- Medir latência e throughput
- Avaliar saturação do Model Server / GPU
- Gerar métricas comparáveis com Prometheus/Grafana

## Modelo Avaliado

- Modelo: Qwen3-VL
- Serving: vLLM
- Endpoint: OpenAI-compatible (`/v1/chat/completions`)
- Plataforma: OpenShift AI

## Entrada Multimodal

As requisições incluem:
- Prompt textual: *“Descreva a imagem em português.”*
- Imagem via URL (lista em `images.txt`)

## Arquitetura do Teste

- Função `run_inference`: executa uma inferência multimodal individual
- Função `load_test`: executa inferências concorrentes com `ThreadPoolExecutor`

## Métricas Coletadas

- Latência média
- Latência P95
- Latência máxima
- Throughput (req/s)
- Throughput de tokens (tokens/s)
- Tokens de entrada e saída
- Erros

## Throughput de Tokens

Calculado como:

```
total_completion_tokens / tempo_total_do_teste
```

## Comportamento sob Carga

- Aumento progressivo de latência
- Platô de tokens/s
- Saturação previsível da GPU
- Crescimento da fila interna do vLLM

## Observabilidade

Métricas client-side correlacionadas com:
- Prometheus (`/metrics` do vLLM)
- Grafana (GPU, VRAM, tokens/s, erros)

#### A seguir tutorial de como habilitar Prometheus e Grafana como plataforma de Observabilidade para o Modelo em Servidor vLLM


# **Tutorial completo de Observabilidade**

## **Observabilidade de vLLM no OpenShift AI**

**Prometheus (User Workload) \+ Grafana \+ Dashboard customizado**

---

## **Objetivo**

* Coletar métricas do **vLLM** (`/metrics`)  
* Usar o **Prometheus User Workload**  
* Consultar métricas corretamente via **Thanos Querier**  
* Visualizar tudo em um **Grafana customizado**  
* Ter dashboards claros para **throughput, latência e saturação de GPU**

---

## **Arquitetura final (mental model)**

```
vLLM (/metrics)
   ↓
ServiceMonitor
   ↓
Prometheus User Workload
   ↓
Thanos Querier (openshift-monitoring)
   ↓
Grafana (Grafana Operator)
```

---

## **1\. Habilitar o User Workload Monitoring**

O OpenShift **não coleta métricas de workloads por padrão**.

Crie (ou edite):

```
apiVersion: v1
kind: ConfigMap
metadata:
  name: cluster-monitoring-config
  namespace: openshift-monitoring
data:
  config.yaml: |
    enableUserWorkload: true
```

Verifique:

```shell
oc get ns openshift-user-workload-monitoring
```

E se o Prometheus está rodando:

```shell
oc get pods -n openshift-user-workload-monitoring
```

---

## **2\. Expor métricas do vLLM corretamente**

O vLLM já expõe métricas em:

```
GET /metrics
```

Exemplo de Service:

```
apiVersion: v1
kind: Service
metadata:
  name: vllm-metrics
  labels:
    app: vllm
spec:
  ports:
    - name: metrics
      port: 8080
      targetPort: 8080
  selector:
    app: vllm
```

---

## **3\. Validar o ServiceMonitor** 

```
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: vllm
  namespace: <seu-namespace>
spec:
  selector:
    matchLabels:
      app: vllm
  endpoints:
    - port: metrics
      path: /metrics
      interval: 15s
```

Confirmação rápida:

```shell
oc -n openshift-user-workload-monitoring get servicemonitors
```

---

## **4\. Validar métricas no OpenShift (sanity check)**

Antes de Grafana, **sempre valide aqui**:

* OpenShift Console  
* **Observe → Metrics**

Teste:

```
{__name__=~"vllm.*"}
```

Se aparecer → coleta OK.

---

## **5\. Instalar o Grafana Operator**

Via OperatorHub:

* **Grafana Operator**  
* **Instale em All Namespaces**  
* Criar as Insâncias do Grafana (próximos passos) no Namespace dedicado (ex: `grafana`)

---

## **6\. Criar a instância do Grafana**

```
apiVersion: grafana.integreatly.org/v1beta1
kind: Grafana
metadata:
  name: grafana
  namespace: grafana
  labels:
    dashboards: vllm
spec:
  ingress:
    enabled: true
```

Criar a rota (o Ingress enabled não é válido nesta versão):

```shell
oc expose svc <seu-grafana-service> -n grafana
```

---

## **7\. O erro clássico (e a correção certa)**

### **NÃO use (no GrafanaDatasource):**

```
prometheus-user-workload.openshift-user-workload-monitoring.svc:9091
```

Esse endpoint **só expõe `/metrics`**, não PromQL.

---

## **Endpoint CORRETO para GrafanaDatasource**

👉 **Thanos Querier**

```
https://thanos-querier.openshift-monitoring.svc:9091
```

Esse é o endpoint **oficial e suportado**.

---

## **8\. Criar o token correto (sem expirar)**

Crie um **ServiceAccount dedicado**:

```shell
oc create sa grafana-prometheus -n grafana
```

Associe a role:

```shell
oc adm policy add-cluster-role-to-user \
  cluster-monitoring-view \
  -z grafana-prometheus \
  -n grafana
```

Pegue o token:

```shell
oc create token grafana-prometheus -n grafana
```

---

## **9. Criar o GrafanaDatasource (funcional)**

```
apiVersion: grafana.integreatly.org/v1beta1
kind: GrafanaDatasource
metadata:
  name: prometheus-ds
  namespace: grafana
spec:
  datasource:
    name: Prometheus
    type: prometheus
    access: proxy
    url: https://thanos-querier.openshift-monitoring.svc:9091
    isDefault: true
    jsonData:
      tlsSkipVerify: true
      httpMethod: POST
    secureJsonData:
      httpHeaderValue1: "Bearer <TOKEN_DO_SA>"
  instanceSelector:
    matchLabels:
      dashboards: vllm
```

---

## **10\. Testes no Grafana → Explore**

Teste nesta ordem:

```
up
```

```
{__name__=~"vllm.*"}
```

```
sum(rate(vllm:prompt_tokens_total[1m]))
```

Se isso funciona → tudo OK.

---

## **11\. Queries vLLM usadas no Dashboard**

### **Throughput**

```
sum(rate(vllm:prompt_tokens_total[1m]))
```

### **Requests em execução**

```
sum(vllm:num_requests_running)
```

### **Requests em fila**

```
sum(vllm:num_requests_waiting)
```

### **Latência p95**

```
histogram_quantile(
  0.95,
  sum by (le)(rate(vllm:request_latency_seconds_bucket[5m]))
)
```

---

## **12\. Organização visual do Dashboard**

* Grid de **24 colunas**  
* Panels lado a lado (`w=6`, `w=8`, `w=12`)  
* Uso de **Rows**:  
  * Overview  
  * Latency  
  * Queue & Saturation  
  * GPU

Layout típico:

```
[ Tokens/s | Running | Waiting | Errors ]
[        Latency p50 / p95 / p99          ]
[     GPU Util     |    GPU Memory        ]
```



