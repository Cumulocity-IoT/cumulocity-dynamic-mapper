#!/bin/sh
###
# Script to 
# 1. create a sample device 
# 2. send a sample uplink event to, that is decode as a measurement
###

# c8y devices create --name "PDW23 - 102030" --type "wika_PGW23" --data "revision=PGW23.100.11"

c8y devices list --type "wika_PGW23" | c8y events create  --template ./event_01.jsonnet 