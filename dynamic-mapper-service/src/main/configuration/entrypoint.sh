#!/bin/sh
#
# Copyright (c) 2026 Cumulocity GmbH.
#
# SPDX-License-Identifier: Apache-2.0
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#
#  @authors Christof Strack, Stefan Witschel
#
#

if [ -n "$MEMORY_LIMIT" ];
 then
  value=$(numfmt  --from=auto  --grouping $MEMORY_LIMIT)
  value=$(($value/1048576)) # convert to MB#
  echo "MEMORY_LIMIT: ${value}MB"
  memory_left=$(awk "BEGIN { memory = int($value * 0.1); if (memory <50) {memory = 50} print memory} ")
  echo "${memory_left}MB is left for system"
  value=$(awk "BEGIN { print(int($value - $memory_left))}") # leave memory space for system
  echo "${value}MB is left for application"
  if [ $value -lt "128" ]; # if less then 128MB fail
  then
    echo "Memory left for application is to small must be at lest 128MB"
    exit 1;
   else
    metaspace=$(awk "BEGIN { memory= int($value * 0.1); if (memory >1024) {memory = 1024} else if ( memory < 64 ){ memory = 64 } print memory} ") # take 10% of available memory to metaspace
    heap=$(($value - $metaspace))
  fi

  jvm_heap=""
  jvm_metaspace=""
  jvm_variable_heap="-Xmx${heap}m"

  echo "Using GraalVM memory settings"
  jvm_variable_metaspace="-XX:MaxMetaspaceSize=${metaspace}m"

  export JAVA_MEM="${jvm_heap:-`echo $jvm_variable_heap`} ${jvm_metaspace:-`echo $jvm_variable_metaspace`}"
  echo "Java Memory Settings: $JAVA_MEM, memory limit: $MEMORY_LIMIT"
fi

jvm_gc=${JAVA_GC:-"-XX:+UseG1GC -XX:+UseStringDeduplication -XX:MinHeapFreeRatio=25 -XX:MaxHeapFreeRatio=75"}
jvm_mem=${JAVA_MEM:-" "}
jvm_opts=${JAVA_OPTS:-"-server -XX:HeapDumpPath=/var/log/dynamic-mapper-service/heap-dump-%p.hprof"}
arguments=${ARGUMENTS:-" --package.name=dynamic-mapper-service --package.directory=dynamic-mapper-service"}
graal_optimiziation="-Dpolyglot.engine.Compilation=false"
#graal_optimiziation="-Dpolyglot.engine.Mode=latency"

proxy_params=""
if [ -n "$PROXY_HTTP_HOST" ]; then proxy_params="-Dhttp.proxyHost=${PROXY_HTTP_HOST} -DproxyHost=${PROXY_HTTP_HOST}"; fi
if [ -n "$PROXY_HTTP_PORT" ]; then proxy_params="${proxy_params} -Dhttp.proxyPort=${PROXY_HTTP_PORT} -DproxyPort=${PROXY_HTTP_PORT}"; fi
if [ -n "$PROXY_HTTP_NON_PROXY_HOSTS" ]; then proxy_params="${proxy_params} -Dhttp.nonProxyHosts=\"${PROXY_HTTP_NON_PROXY_HOSTS}\""; fi
if [ -n "$PROXY_HTTPS_HOST" ]; then proxy_params="${proxy_params} -Dhttps.proxyHost=${PROXY_HTTPS_HOST}"; fi
if [ -n "$PROXY_HTTPS_PORT" ]; then proxy_params="${proxy_params} -Dhttps.proxyPort=${PROXY_HTTPS_PORT}"; fi
if [ -n "$PROXY_SOCKS_HOST" ]; then proxy_params="${proxy_params} -DsocksProxyHost=${PROXY_SOCKS_HOST}"; fi
if [ -n "$PROXY_SOCKS_PORT" ]; then proxy_params="${proxy_params} -DsocksProxyPort=${PROXY_SOCKS_PORT}"; fi

otel_agent_attach=""
lower_case_javaagent_enabled=$(echo "${OTEL_JAVAAGENT_ENABLED}" | tr '[:upper:]' '[:lower:]')
if [ $lower_case_javaagent_enabled = "true" ];
then
  otel_agent_attach="-javaagent:/otel/opentelemetry-javaagent.jar";
fi
echo "otel_agent_attach = ${otel_agent_attach}"

mkdir -p /var/log/dynamic-mapper-service; echo "heap dumps  /var/log/dynamic-mapper-service/heap-dump-<pid>.hprof"

java ${jvm_opts} ${jvm_gc} ${jvm_mem} ${proxy_params} ${graal_optimiziation} ${otel_agent_attach} -jar /dynamic-mapper-service/dynamic-mapper-service.jar ${arguments}
