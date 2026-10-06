variable "region" {
  description = "AWS region"
  type        = string
  default     = "ca-central-1"
}

variable "name" {
  description = "Prefix for every resource"
  type        = string
  default     = "fulfillops"
}

variable "image_tag" {
  description = "Image tag pushed to the ECR repositories (e.g. the git SHA)"
  type        = string
  default     = "latest"
}

variable "backend_desired_count" {
  description = "Backend tasks. More than one is safe: job polling uses FOR UPDATE SKIP LOCKED with leases"
  type        = number
  default     = 2
}

variable "web_desired_count" {
  type    = number
  default = 2
}

variable "deploy_carrier_sim" {
  description = "Run the carrier simulator (demo/staging only). Set false and point carrier_url at a real carrier."
  type        = bool
  default     = true
}

variable "carrier_url" {
  description = "Carrier API base URL when deploy_carrier_sim = false"
  type        = string
  default     = ""
}

variable "seed_demo_users" {
  description = "Create the sales/warehouse/supervisor demo accounts (demo environments only)"
  type        = bool
  default     = true
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "db_multi_az" {
  description = "Standby replica in a second AZ (recommended for production)"
  type        = bool
  default     = false
}

variable "certificate_arn" {
  description = "ACM certificate for HTTPS. Empty = HTTP only (demo)."
  type        = string
  default     = ""
}
