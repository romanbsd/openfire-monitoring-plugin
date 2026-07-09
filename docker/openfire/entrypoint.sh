#!/bin/bash
set -e

rewire_openfire() {
  rm -rf ${OPENFIRE_DIR}/{conf,resources/security}
  ln -sf ${OPENFIRE_DATA_DIR}/conf ${OPENFIRE_DIR}/
  ln -sf ${OPENFIRE_DATA_DIR}/plugins ${OPENFIRE_DIR}/
  ln -sf ${OPENFIRE_DATA_DIR}/conf/security ${OPENFIRE_DIR}/resources/
}

initialize_data_dir() {
  echo "Initializing ${OPENFIRE_DATA_DIR}..."

  mkdir -p ${OPENFIRE_DATA_DIR}
  chmod -R 0750 ${OPENFIRE_DATA_DIR}
  chown -R ${OPENFIRE_USER}:${OPENFIRE_USER} ${OPENFIRE_DATA_DIR}

  if [[ ! -d ${OPENFIRE_DATA_DIR}/conf ]]; then
    cp -a ${OPENFIRE_DIR}/conf_org ${OPENFIRE_DATA_DIR}/conf
    cp -a ${OPENFIRE_DIR}/plugins_org ${OPENFIRE_DATA_DIR}/plugins
    cp -a ${OPENFIRE_DIR}/resources/security_org ${OPENFIRE_DATA_DIR}/conf/security
    if [[ -f /opt/openfire-config/openfire.xml ]]; then
      cp /opt/openfire-config/openfire.xml ${OPENFIRE_DATA_DIR}/conf/openfire.xml
    fi
  fi

  mkdir -p ${OPENFIRE_DATA_DIR}/{plugins,embedded-db}
  rm -rf ${OPENFIRE_DATA_DIR}/plugins/admin
  ln -sf ${OPENFIRE_DIR}/plugins_org/admin ${OPENFIRE_DATA_DIR}/plugins/admin
  chown -R ${OPENFIRE_USER}:${OPENFIRE_USER} ${OPENFIRE_DATA_DIR}

  CURRENT_VERSION=
  [[ -f ${OPENFIRE_DATA_DIR}/VERSION ]] && CURRENT_VERSION=$(cat ${OPENFIRE_DATA_DIR}/VERSION)
  if [[ ${OPENFIRE_VERSION} != ${CURRENT_VERSION} ]]; then
    echo -n "${OPENFIRE_VERSION}" > "${OPENFIRE_DATA_DIR}/VERSION"
    chown ${OPENFIRE_USER}:${OPENFIRE_USER} "${OPENFIRE_DATA_DIR}/VERSION"
  fi
}

initialize_log_dir() {
  echo "Initializing ${OPENFIRE_LOG_DIR}..."
  mkdir -p ${OPENFIRE_LOG_DIR}
  chmod -R 0755 ${OPENFIRE_LOG_DIR}
  chown -R ${OPENFIRE_USER}:${OPENFIRE_USER} ${OPENFIRE_LOG_DIR}
  rm -rf ${OPENFIRE_DIR}/logs
  ln -sf ${OPENFIRE_LOG_DIR} ${OPENFIRE_DIR}/logs
}

copy_provided_plugins() {
  # Always overwrite image-provided plugins so `docker compose build` + restart
  # picks up a newer monitoring.jar even when the data volume already has one.
  # (cp --update=none previously left a stale jar in place forever.)
  for file in /opt/plugins/*.jar; do
    if [ -f "$file" ]; then
      local base
      base="$(basename "$file")"
      echo "Installing image-provided plugin: ${base}"
      cp -f "$file" "${OPENFIRE_DIR}/plugins/${base}"
      # Force Openfire to re-extract the plugin on next start.
      rm -rf "${OPENFIRE_DIR}/plugins/${base%.jar}"
    fi
  done
}

apply_search_config() {
  local target="${OPENFIRE_DATA_DIR}/conf/openfire.xml"
  if [[ ! -f "${target}" ]]; then
    return 0
  fi
  if grep -Fq '<host>opensearch</host>' "${target}"; then
    return 0
  fi

  echo "Applying OpenSearch configuration to ${target}"
  if ! grep -q '<conversation>' "${target}"; then
    xmlstarlet ed -L -s "/jive" -t elem -n "conversation" -v "" "${target}"
  fi
  if grep -q '<search>' "${target}"; then
    xmlstarlet ed -L -d "/jive/conversation/search" "${target}"
  fi
  xmlstarlet ed -L \
    -s "/jive/conversation" -t elem -n "search" -v "" \
    -s "/jive/conversation/search" -t elem -n "index-enabled" -v "true" \
    -s "/jive/conversation/search" -t elem -n "updateInterval" -v "5" \
    -s "/jive/conversation/search" -t elem -n "opensearch" -v "" \
    -s "/jive/conversation/search/opensearch" -t elem -n "host" -v "opensearch" \
    -s "/jive/conversation/search/opensearch" -t elem -n "port" -v "9200" \
    -s "/jive/conversation/search/opensearch" -t elem -n "scheme" -v "http" \
    -s "/jive/conversation/search/opensearch" -t elem -n "username" -v "" \
    -s "/jive/conversation/search/opensearch" -t elem -n "password" -v "" \
    -s "/jive/conversation/search/opensearch" -t elem -n "indexPrefix" -v "monitoring" \
    "${target}"
  chown ${OPENFIRE_USER}:${OPENFIRE_USER} "${target}"
}

if [[ ${1:0:1} = '-' ]]; then
  EXTRA_ARGS="$@"
  set --
fi

rewire_openfire
initialize_data_dir
apply_search_config
initialize_log_dir
copy_provided_plugins

export OPENFIRE_HOME="${OPENFIRE_DIR}"

if [[ -z ${1} ]]; then
  exec start-stop-daemon --start --chuid ${OPENFIRE_USER}:${OPENFIRE_USER} \
    --exec "${OPENFIRE_DIR}/bin/openfire.sh" -- ${EXTRA_ARGS}
else
  exec "$@"
fi
