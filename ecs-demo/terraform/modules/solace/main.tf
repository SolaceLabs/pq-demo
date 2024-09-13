module "partitioned_queue" {
  source  = "SolaceProducts/queue-endpoint/solacebroker"
  version = "1.0.0"

  msg_vpn_name = var.solace_vpn_name
  endpoint_type = "queue"
  endpoint_name = var.queue_name
  queue_subscription_topics = ["test/>"]
  permission = "consume"
  access_type = "non-exclusive"
  partition_count = var.partition_count
}
