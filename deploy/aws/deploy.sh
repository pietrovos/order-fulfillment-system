#!/usr/bin/env bash
# Build, push and roll out a version. Assumes `terraform apply` has created the infrastructure and that
# AWS credentials for the target account are configured.
#   deploy/aws/deploy.sh <image-tag>
set -euo pipefail
tag=${1:?usage: deploy.sh <image-tag>}
cd "$(dirname "$0")"
region=$(terraform output -raw region)
registry=$(terraform output -json ecr_repositories | jq -r '.backend' | cut -d/ -f1)
aws ecr get-login-password --region "$region" | docker login --username AWS --password-stdin "$registry"
for svc in backend web carrier-sim; do
  repo=$(terraform output -json ecr_repositories | jq -r --arg s "$svc" '.[$s]')
  docker build -t "$repo:$tag" "../../${svc/web/frontend}"
  docker push "$repo:$tag"
done
terraform apply -var "image_tag=$tag"
