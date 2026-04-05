# Flow Forge

A declarative Java framework for managing multi-step event-driven workflows, inspired by the Axon workflow approach.

## Project Structure

This is a multi-module Maven project with the following modules:

- **flow-forge-core** - Framework-agnostic core library with annotations and workflow engine
- **flow-forge-storage-postgres** - PostgreSQL storage provider
- **flow-forge-storage-mongodb** - MongoDB storage provider
- **flow-forge-storage-mysql** - MySQL storage provider
- **flow-forge-spring-boot-starter** - Spring Boot auto-configuration

## Getting Started

### Maven Dependency

Add the core library and your chosen storage provider:

```xml
<dependency>
    <groupId>io.github.gkosharovdev</groupId>
    <artifactId>flow-forge-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>io.github.gkosharovdev</groupId>
    <artifactId>flow-forge-storage-postgres</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

For Spring Boot applications:

```xml
<dependency>
    <groupId>io.github.gkosharovdev</groupId>
    <artifactId>flow-forge-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Usage Example


`@Workflow
@Component
public class OrderProcessingworkflow {

    @WorkflowId
    private String workflowId;

    @StartWorkflow
    @FlowStep(associationProperty = "orderId")
    public void processPayment(OrderCreatedEvent event) {
        // Initialize workflow
        this.workflowId = event.getOrderId();
        // Process payment and invoice
    }

    @FlowStep(associationProperty = "orderId")
    public void reserveStock(PaymentProcessedEvent event) {
        // Reserve stock in warehouse
    }

    @EndWorkflow
    @FlowStep(associationProperty = "orderId")
    public void requestDelivery(OrderCompletedEvent event) {
        // Request delivery
    }

    @workflowCompensation(forStep = "processPayment")
    public void compensatePayment() {
        // Compensation logic for payment
    }

    @workflowCompensation(forStep = "reserveStock")
    public void compensateStock() {
        // Compensation logic for stock reservation in the warehouse
    }
}`
