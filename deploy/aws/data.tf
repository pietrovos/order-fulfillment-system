# ---------------------------------------------------------------- container registry
resource "aws_ecr_repository" "repo" {
  for_each             = toset(["backend", "web", "carrier-sim"])
  name                 = "${var.name}/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false
  image_scanning_configuration {
    scan_on_push = true
  }
}

# ---------------------------------------------------------------- database
resource "aws_db_subnet_group" "main" {
  name       = var.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "main" {
  identifier                   = var.name
  engine                       = "postgres"
  engine_version               = "17"
  instance_class               = var.db_instance_class
  allocated_storage            = 20
  max_allocated_storage        = 100
  storage_encrypted            = true
  db_name                      = "fulfillops"
  username                     = "fulfillops"
  manage_master_user_password  = true # RDS keeps the password in Secrets Manager and rotates it
  db_subnet_group_name         = aws_db_subnet_group.main.name
  vpc_security_group_ids       = [aws_security_group.db.id]
  multi_az                     = var.db_multi_az
  backup_retention_period      = 7
  deletion_protection          = true
  skip_final_snapshot          = false
  final_snapshot_identifier    = "${var.name}-final"
  performance_insights_enabled = true
}

# ---------------------------------------------------------------- application secrets
resource "random_bytes" "jwt" {
  length = 48
}

resource "aws_secretsmanager_secret" "jwt" {
  name = "${var.name}/jwt-secret"
}

resource "aws_secretsmanager_secret_version" "jwt" {
  secret_id     = aws_secretsmanager_secret.jwt.id
  secret_string = random_bytes.jwt.base64
}
