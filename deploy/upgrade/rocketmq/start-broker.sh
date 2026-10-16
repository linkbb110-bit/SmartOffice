#!/usr/bin/env bash
set -euo pipefail

# Java remoting clients receive this IP from NameServer. Advertise an address
# reachable from booking-service on the Compose bridge, never 127.0.0.1.
broker_ip="$(hostname -i | awk '{print $1}')"
if [[ -z "$broker_ip" ]]; then
  echo 'Cannot determine the local broker container IP' >&2
  exit 1
fi
cp /opt/smartoffice/broker.conf /tmp/smartoffice-broker.conf
printf '\nbrokerIP1=%s\n' "$broker_ip" >> /tmp/smartoffice-broker.conf
exec sh mqbroker -n "${NAMESRV_ADDR:?NAMESRV_ADDR is required}" -c /tmp/smartoffice-broker.conf
