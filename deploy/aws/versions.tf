terraform {
  required_version = ">= 1.6"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.70" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
  # Configure a remote backend before first apply, e.g.:
  # backend "s3" { bucket = "my-tf-state" key = "fulfillops/terraform.tfstate" region = "ca-central-1" }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = var.name, ManagedBy = "terraform" }
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, 2)
}
