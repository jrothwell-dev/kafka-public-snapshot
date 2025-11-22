#!/bin/bash
# Seed the required.wwcc.users topic with who needs WWCC

echo '{"email":"jordanr@murrumbidgee.nsw.gov.au","department":"IT Services","requires_wwcc":true}' | \
  docker exec -i kafka kafka-console-producer --topic required.wwcc.users --bootstrap-server localhost:9092

echo '{"email":"other.user@murrumbidgee.nsw.gov.au","department":"Youth Services","requires_wwcc":true}' | \
  docker exec -i kafka kafka-console-producer --topic required.wwcc.users --bootstrap-server localhost:9092