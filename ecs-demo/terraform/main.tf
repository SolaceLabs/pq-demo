provider "aws" {
  region = var.aws_region
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

# Call the ECS module
module "ecs" {
  source = "./modules/ecs"

  aws_region         = var.aws_region
  vpc_id             = aws_vpc.main.id
  vpc_cidr_block = aws_vpc.main.cidr_block
  subnet_ids         = [aws_subnet.subnet_1.id, aws_subnet.subnet_2.id]
  security_group_ids = [aws_security_group.pq_subscriber_sg.id]
  pq_subscriber_docker_image       = var.pq_subscriber_docker_image
  solace_vpn         = var.solace_vpn
  solace_username    = var.solace_username
  solace_queue       = var.solace_queue
  sub_ack_window     = var.sub_ack_window
  solace_host        = var.solace_host
  solace_password    = var.solace_password
}

module "solace" {
  source = "./modules/solace"
  solace_semp_url = var.solace_semp_host
  solace_vpn_name = var.solace_vpn
  solace_semp_username = var.solace_semp_username
  solace_semp_password = var.solace_semp_password
  partition_count = var.solace_queue_partition_count
  queue_name = var.solace_queue

}