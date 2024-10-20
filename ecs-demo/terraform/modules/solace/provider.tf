terraform {
  required_providers {
    solacebroker = {
      source = "SolaceProducts/solacebroker"
      version = "1.0.1"
    }
  }
}

provider "solacebroker" {
  url = var.solace_semp_url
  username = var.solace_semp_username
  password = var.solace_semp_password
}
