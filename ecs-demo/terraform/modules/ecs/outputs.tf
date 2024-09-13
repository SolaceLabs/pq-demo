output "ecs_cluster_arn" {
  value = aws_ecs_cluster.pq_demo_cluster.arn
}

output "ecs_service_name" {
  value = aws_ecs_service.pq_subscriber_service.name
}