locals {
  repo        = { for k, r in aws_ecr_repository.repo : k => r.repository_url }
  carrier_url = var.deploy_carrier_sim ? "http://carrier.${var.name}.local:8091" : var.carrier_url
}

resource "aws_ecs_cluster" "main" {
  name = var.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "app" {
  for_each          = toset(["backend", "web", "carrier-sim"])
  name              = "/ecs/${var.name}/${each.key}"
  retention_in_days = 30
}

# Private DNS so the backend can reach the carrier simulator as carrier.<name>.local.
resource "aws_service_discovery_private_dns_namespace" "main" {
  name = "${var.name}.local"
  vpc  = aws_vpc.main.id
}

resource "aws_service_discovery_service" "carrier" {
  name = "carrier"
  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.main.id
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
}

# ---------------------------------------------------------------- IAM
data "aws_iam_policy_document" "assume_ecs" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.name}-task-execution"
  assume_role_policy = data.aws_iam_policy_document.assume_ecs.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "read_secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.jwt.arn, aws_db_instance.main.master_user_secret[0].secret_arn]
  }
}

resource "aws_iam_role_policy" "read_secrets" {
  name   = "read-app-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.read_secrets.json
}

# ---------------------------------------------------------------- backend
resource "aws_ecs_task_definition" "backend" {
  family                   = "${var.name}-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.execution.arn
  container_definitions = jsonencode([{
    name         = "backend"
    image        = "${local.repo["backend"]}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 8080 }]
    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
      { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:5432/fulfillops" },
      { name = "DB_USER", value = "fulfillops" },
      { name = "CARRIER_URL", value = local.carrier_url },
      { name = "SEED_DEMO_USERS", value = tostring(var.seed_demo_users) },
    ]
    secrets = [
      { name = "DB_PASSWORD", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::" },
      { name = "JWT_SECRET", valueFrom = aws_secretsmanager_secret.jwt.arn },
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app["backend"].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "backend"
      }
    }
  }])
}

resource "aws_ecs_service" "backend" {
  name            = "backend"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.backend.arn
  desired_count   = var.backend_desired_count
  launch_type     = "FARGATE"
  # Flyway runs on startup; a rolling deploy keeps old tasks serving until new ones pass health checks.
  deployment_minimum_healthy_percent = 50
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = 90
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets         = aws_subnet.private[*].id
    security_groups = [aws_security_group.tasks.id]
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.backend.arn
    container_name   = "backend"
    container_port   = 8080
  }
}

# ---------------------------------------------------------------- web
resource "aws_ecs_task_definition" "web" {
  family                   = "${var.name}-web"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution.arn
  container_definitions = jsonencode([{
    name         = "web"
    image        = "${local.repo["web"]}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 8080 }]
    # /api is routed to the backend by the ALB; nginx's own /api proxy points at the ALB for completeness.
    environment = [{ name = "BACKEND_URL", value = "http://${aws_lb.main.dns_name}" }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app["web"].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "web"
      }
    }
  }])
}

resource "aws_ecs_service" "web" {
  name            = "web"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.web.arn
  desired_count   = var.web_desired_count
  launch_type     = "FARGATE"
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets         = aws_subnet.private[*].id
    security_groups = [aws_security_group.tasks.id]
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.web.arn
    container_name   = "web"
    container_port   = 8080
  }
}

# ---------------------------------------------------------------- carrier simulator (demo only)
resource "aws_ecs_task_definition" "carrier" {
  count                    = var.deploy_carrier_sim ? 1 : 0
  family                   = "${var.name}-carrier-sim"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution.arn
  container_definitions = jsonencode([{
    name         = "carrier-sim"
    image        = "${local.repo["carrier-sim"]}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 8091 }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app["carrier-sim"].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "carrier"
      }
    }
  }])
}

resource "aws_ecs_service" "carrier" {
  count           = var.deploy_carrier_sim ? 1 : 0
  name            = "carrier-sim"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.carrier[0].arn
  desired_count   = 1 # bookings are held in memory; exactly one instance
  launch_type     = "FARGATE"
  network_configuration {
    subnets         = aws_subnet.private[*].id
    security_groups = [aws_security_group.tasks.id]
  }
  service_registries {
    registry_arn = aws_service_discovery_service.carrier.arn
  }
}
