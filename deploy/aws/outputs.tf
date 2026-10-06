output "url" {
  value = "${local.https ? "https" : "http"}://${aws_lb.main.dns_name}"
}

output "ecr_repositories" {
  value = local.repo
}

output "cluster" {
  value = aws_ecs_cluster.main.name
}

output "db_endpoint" {
  value = aws_db_instance.main.address
}

output "region" {
  value = var.region
}
