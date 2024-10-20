# ECS Cluster
resource "aws_ecs_cluster" "pq_demo_cluster" {
  name = "pq-demo-cluster"
  # Required for ECS Autoscaler
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

# PQ Subscriber Service
resource "aws_ecs_service" "pq_subscriber_service" {
  name            = "pq-subscriber-service"
  cluster         = aws_ecs_cluster.pq_demo_cluster.id
  task_definition = aws_ecs_task_definition.pq_subscriber_task.arn
  launch_type     = "FARGATE"
  desired_count   = 1

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = var.security_group_ids
    assign_public_ip = true
  }
}

resource "aws_iam_role" "ecs_execution_role" {
  name = "ecs_execution_role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role" "ecs_task_role" {
  name = "ecs_task_role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_execution_role_policy" {
  role       = aws_iam_role.ecs_execution_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "secrets_manager_access" {
  name = "secrets_manager_access"
  role = aws_iam_role.ecs_execution_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue"
        ]
        Resource = [
          aws_secretsmanager_secret.solace_host.arn,
          aws_secretsmanager_secret.solace_password.arn
        ]
      }
    ]
  })
}

# PQ Subscriber Task Definition
resource "aws_ecs_task_definition" "pq_subscriber_task" {
  family                   = "pq-subscriber-task"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.ecs_execution_role.arn
  task_role_arn            = aws_iam_role.ecs_task_role.arn

  container_definitions = jsonencode([{
    name  = "pq-subscriber"
    image = var.pq_subscriber_docker_image
    environment = [
      { name = "VPN_NAME", value = var.solace_vpn },
      { name = "USERNAME", value = var.solace_username },
      { name = "QUEUE_NAME", value = var.solace_queue },
      { name = "SUB_ACK_WINDOW_SIZE", value = var.sub_ack_window }
    ]
    secrets = [
      { name = "HOST", valueFrom = aws_secretsmanager_secret.solace_host.arn },
      { name = "PASSWORD", valueFrom = aws_secretsmanager_secret.solace_password.arn }
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.pq_subscriber_logs.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "ecs"
      }
    }
  }])
}

# Secrets for PQSubscriber
resource "aws_secretsmanager_secret" "solace_host" {
  name = "pq_demo_solace_host"
  recovery_window_in_days = 0
  force_overwrite_replica_secret = true
}

resource "aws_secretsmanager_secret_version" "solace_host" {
  secret_id     = aws_secretsmanager_secret.solace_host.id
  secret_string = var.solace_host
}

resource "aws_secretsmanager_secret" "solace_password" {
  name = "pq_demo_solace_password"
  recovery_window_in_days = 0
  force_overwrite_replica_secret = true
}

resource "aws_secretsmanager_secret_version" "solace_password" {
  secret_id     = aws_secretsmanager_secret.solace_password.id
  secret_string = var.solace_password
}
# PQ Subscriber Logs
resource "aws_cloudwatch_log_group" "pq_subscriber_logs" {
  name              = "/ecs/pq-subscriber"
  retention_in_days = 30
}
