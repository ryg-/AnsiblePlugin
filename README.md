# IntelliJ Ansible Plugin
[![Build](https://github.com/MSDehghan/AnsiblePlugin/workflows/Build/badge.svg)](https://github.com/MSDehghan/AnsiblePlugin/actions?query=workflow%3ABuild)
[![Version](https://img.shields.io/jetbrains/plugin/v/ir.msdehghan.plugins.ansible.svg)](https://plugins.jetbrains.com/plugin/14893-ansible)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/ir.msdehghan.plugins.ansible.svg)](https://plugins.jetbrains.com/plugin/14893-ansible)

Plugin that helps develop ansible files.

## Features
* Autocomplete playbooks fields
* Autocomplete role fields
* Discover Ansible modules and their option docs from the project's installed Ansible environment, including collections
* Navigate between role declarations, `include_role` / `import_role`, role handlers, templates, files, and statically identifiable variables
* Resolve role roots from the local `roles/` directory and `ansible.cfg` `roles_path`

## Project setup

The plugin runs `ansible-doc` from the project root and inherits the IDE process environment, so the selected Python environment and installed collections determine the module catalog. Configure a different executable under **Settings | Tools | Ansible**. The executable is stored in your IDE user settings for that project; workspace files cannot choose a program to run. The same page shows catalog load errors and can refresh the catalog.

Role lookup uses the project `roles/` directory and `roles_path` in `ansible.cfg`. Relative `roles_path` entries are resolved from the configuration file. Static variable navigation covers role defaults and vars, play/task vars, `set_fact`, `register`, group/host vars, and YAML inventory; it shows plausible sources without attempting runtime precedence evaluation.

## Contribution
To report bugs and feature requests you can create a Github issue. For code contribution you can create pull requests.

Any type of contribution is **highly** appreciated.
