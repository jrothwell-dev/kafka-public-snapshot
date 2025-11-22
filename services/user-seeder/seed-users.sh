#!/bin/bash
# Seed the required.wwcc.users topic with who needs WWCC

@echo '{"email":"jordanr@murrumbidgee.nsw.gov.au","department":"IT Services","position":"Systems Administrator","requiresWwcc":true,"startDate":"2024-01-15"}' | \
  docker exec -i kafka kafka-console-producer --topic reference.wwcc.required --bootstrap-server localhost:9092
@echo '{"email":"sarah.mitchell@murrumbidgee.nsw.gov.au","department":"Community Services","position":"Youth Worker","requiresWwcc":true,"startDate":"2024-03-01"}' | \
  docker exec -i kafka kafka-console-producer --topic reference.wwcc.required --bootstrap-server localhost:9092
@echo '{"email":"james.peterson@murrumbidgee.nsw.gov.au","department":"Youth Programs","position":"Program Coordinator","requiresWwcc":true,"startDate":"2025-12-01"}' | \
  docker exec -i kafka kafka-console-producer --topic reference.wwcc.required --bootstrap-server localhost:9092
@echo '{"email":"emily.rodriguez@murrumbidgee.nsw.gov.au","department":"Recreation Services","position":"Sports Coach","requiresWwcc":true,"startDate":"2026-01-15"}' | \
  docker exec -i kafka kafka-console-producer --topic reference.wwcc.required --bootstrap-server localhost:9092