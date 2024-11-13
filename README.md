# ODCD (OpenTelemetry Data Collector Drivers)

**[Semantic Convention](docs/semconv)** |
**[Changelog](CHANGELOG.md)** |
**[Contributing](CONTRIBUTING.md)** |
**[License](LICENSE)**


---

ODCD (OpenTelemetry Data Collector Drivers) is a collection of standalone OpenTelemetry receivers designed for databases, systems, applications, and more. Each implementation follows the OpenTelemetry Semantic Conventions. A standard OTLP exporter is included to send data from this "Data Collector" to an OpenTelemetry backend or collector.

## Available Data Collectors

- [OTel Data Collector for Relational Databases](rdb/README.md) (**Java 8+**)
- [OTel Data Collector for Host](host/README.md) (**Java 11+**)
- [OTel Data Collector for LLM](llm/README.md) (**Java 11+**)

## Common Configuration Parameters

| Parameter                 | Scope     | Description                                                                                                          | Example                |
|---------------------------|-----------|----------------------------------------------------------------------------------------------------------------------|------------------------|
| `otel.backend.url`        | instance  | The OTLP URL of the telemetry backend, e.g., `http://localhost:4317` (gRPC) or `http://localhost:4318/v1/metrics` (HTTP) | `http://127.0.0.1:4317`  |  
| `otel.backend.using.http` | instance  | Set to `false` to use OTLP/gRPC (default), or `true` to use OTLP/HTTP                                               | `false`                |  
| `otel.service.name`       | instance  | The name of the OTel service (required by OpenTelemetry)                                                            | `DamengDC`             |  
| `otel.service.instance.id`| instance  | The OTel service instance ID (the identifier for the database entity, which can be generated by the Data Collector if not provided)               | `1.2.3.4:5236@MYDB`    |  
| `poll.interval`           | instance  | The interval, in seconds, for querying metrics                                                                      | `25`                   |  
| `callback.interval`       | instance  | The interval, in seconds, for sending data to the backend                                                           | `30`                   |
