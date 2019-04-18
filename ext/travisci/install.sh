#!/bin/bash

set -x
set -e

get_dep_version() {
    local package="${1:?}"

    lein with-profile +ci pprint :dependencies | awk 'BEGIN { FS = "[]([\" ]+" } $2=="'"${package}"'" { print $3; exit }'
}

checkout_dependency() {
    local package="${1:?}"
    local version
    local checkout_dir

    version="$(get_dep_version "${package}")"
    checkout_dir="checkouts/$(echo "${package}" | cut -d/ -f2)"

    git clone --branch "${version}" --depth 1 "git@github.com:${package}" "${checkout_dir}"

    pushd "${checkout_dir}"
    lein install
    popd
}

mkdir -p checkouts

checkout_dependency puppetlabs/dujour
