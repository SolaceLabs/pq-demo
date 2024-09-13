variable "aws_region" {
  description = "The AWS region to create resources in."
  default     = "us-east-2"
}

variable "pq_subscriber_docker_image" {
  description = "Docker image to deploy (e.g., 'your-dockerhub-username/pq-subscriber:latest')"
  type        = string
}

variable "solace_host" {
  description = "Solace host address"
  default     = "localhost:55555"
}

variable "solace_semp_host" {
  description = "Semp URL for the broker"
  default     = "http://localhost:8080"
}

variable "solace_vpn" {
  description = "Solace VPN name"
  default     = "default"
}

variable "solace_semp_username" {
  description = "Semp username"
  default     = "admin"
}

variable "solace_semp_password" {
  description = "SEMP Password"
  default     = "admin"
}

variable "solace_username" {
  description = "Solace username"
  default     = "default"
}

variable "solace_password" {
  description = "Solace password"
  default     = "default"
}

variable "solace_queue" {
  description = "Solace queue name"
  default     = "pq-demo"
}

variable "solace_queue_partition_count" {
  description = "Number Of Partitions To Create On The Queue."
  default     = 5
}

variable "sub_ack_window" {
  description = "Sub Ack Window"
  default     = "64"
}


