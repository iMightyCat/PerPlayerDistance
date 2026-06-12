# PerPlayerDistance

**Per-player view and simulation distance control for Minecraft servers (1.21+)**

[![Spigot](https://img.shields.io/badge/Spigot-1.21+-orange.svg)]()
[![Java](https://img.shields.io/badge/Java-17-blue.svg)]()
[![License](https://img.shields.io/badge/License-MIT-green.svg)]()

## Overview

PerPlayerDistance allows server administrators to set individual view and simulation distances for players, with support for group-based defaults and personal overrides. Perfect for optimizing server performance while giving VIPs or staff members increased render distances.

## Features

- **🎯 Per-Player Distance** – Set custom view/simulation distances for individual players
- **👥 Group-Based Defaults** – Configure distances per LuckPerms group
- **📊 H2 Database** – Lightweight, file-based storage (no external database required)
- **⚡ Instant Application** – Changes apply immediately, no restart needed
- **🔄 LuckPerms Integration** – Automatically updates when a player's group changes
- **🏷️ PlaceholderAPI Support** – Use `%ppd_*%` placeholders in any plugin
- **📝 MiniMessage Formatting** – Fully customizable, gradient-supporting messages
- **🎮 1.21+ Native** – Uses modern Minecraft distance APIs

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/ppd set <player> <view> [sim]` | `ppd.set` | Set personal distances |
| `/ppd reset <player>` | `ppd.reset` | Reset player to defaults |
| `/ppd group <group> set <view> [sim]` | `ppd.group` | Set group distances |
| `/ppd group <group> reset` | `ppd.group` | Reset group distances |
| `/ppd reload` | `ppd.reload` | Reload configuration |

## Placeholders

| Placeholder | Output |
|-------------|--------|
| `%ppd_view%` | Player's current view distance |
| `%ppd_sim%` | Player's current simulation distance |
| `%ppd_group%` | Player's primary LuckPerms group |
| `%ppd_group_view%` | Player's group view distance |
| `%ppd_group_sim%` | Player's group simulation distance |

## Configuration

```yaml
settings:
  min-view-distance: 2
  max-view-distance: 32
  min-simulation-distance: 2
  max-simulation-distance: 32

groups:
  default:
    view: 10
    simulation: 6
  vip:
    view: 16
    simulation: 10
  admin:
    view: 32
    simulation: 16
