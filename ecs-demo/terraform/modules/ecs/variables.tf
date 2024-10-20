variable "aws_region" {}
variable "vpc_id" {}
variable "vpc_cidr_block" {}
variable "subnet_ids" {
  type = list(string)
}
variable "security_group_ids" {
  type = list(string)
}
variable "pq_subscriber_docker_image" {}
variable "solace_vpn" {}
variable "solace_username" {}
variable "solace_queue" {}
variable "sub_ack_window" {}
variable "solace_host" {}
variable "solace_password" {}