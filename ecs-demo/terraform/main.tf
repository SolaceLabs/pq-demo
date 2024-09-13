provider "aws" {
  region = var.aws_region  # Change this to your preferred region
}

# VPC and network resources
resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "pq-demo-vpc"
  }
}

resource "aws_subnet" "subnet_1" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.0.1.0/24"
  availability_zone       = "${var.aws_region}a"
  map_public_ip_on_launch = true

  tags = {
    Name = "pq-demo-subnet-1"
  }
}

resource "aws_subnet" "subnet_2" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.0.2.0/24"
  availability_zone       = "${var.aws_region}b"
  map_public_ip_on_launch = true

  tags = {
    Name = "pq-demo-subnet-2"
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "pq-demo-igw"
  }
}

resource "aws_route_table" "main" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "pq-demo-rt"
  }
}

resource "aws_route_table_association" "subnet_1" {
  subnet_id      = aws_subnet.subnet_1.id
  route_table_id = aws_route_table.main.id
}

resource "aws_route_table_association" "subnet_2" {
  subnet_id      = aws_subnet.subnet_2.id
  route_table_id = aws_route_table.main.id
}

resource "aws_security_group" "pq_subscriber_sg" {
  name        = "pq-subscriber-sg"
  description = "Security group for PQ Subscriber"
  vpc_id      = aws_vpc.main.id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Add ingress rules as needed
}

# ECS Cluster
resource "aws_ecs_cluster" "pq_demo_cluster" {
  name = "pq-demo-cluster"
}

# PQ Subscriber Service
resource "aws_ecs_service" "pq_subscriber_service" {
  name            = "pq-subscriber-service"
  cluster         = aws_ecs_cluster.pq_demo_cluster.id
  task_definition = aws_ecs_task_definition.pq_subscriber_task.arn
  launch_type     = "FARGATE"
  desired_count   = 1

  network_configuration {
    subnets          = [aws_subnet.subnet_1.id, aws_subnet.subnet_2.id]
    security_groups  = [aws_security_group.pq_subscriber_sg.id]
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
    image = var.docker_image
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

