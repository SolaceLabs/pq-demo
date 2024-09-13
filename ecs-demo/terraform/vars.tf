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
}

variable "solace_vpn" {
  description = "Solace VPN name"
  default     = "default"
}

variable "solace_username" {
  description = "Solace username"
  default     = "default"
}

variable "solace_password" {
  description = "Solace password"
}

variable "solace_queue" {
  description = "Solace queue name"
  default     = "pq-demo"
}

variable "sub_ack_window" {
  description = "Sub Ack Window"
  default = "64"
}