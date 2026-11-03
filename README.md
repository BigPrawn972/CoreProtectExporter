# CoreProtectExporter

A separate companion plugin for exporting CoreProtect lookup results to UTF-8 TSV files. It does not modify or include CoreProtect source code or its JAR.

## Features

- Runs one CoreProtect API lookup and writes the returned rows to files under `plugins/CoreProtect/exports/`.
- Splits output into files of 50,000 rows by default. Change this with `split:<rows>`.
- Supports time, action, include/exclude, player, and radius filters.
- Resolves current Bukkit material names such as `grass_block`.

> **Memory note:** CoreProtect's `performLookup` API returns the complete result as a `List<String[]>`. The exporter therefore holds the full result in memory before writing files. Splitting files limits each file's size; it does not limit peak memory use. Very large lookups may exhaust server memory.

## Requirements

- Paper 26.2 or a compatible server exposing Bukkit API 26.2.
- CoreProtect 24.1 or a compatible CoreProtect API v10+ implementation.
- Java 25 to build this project.

CoreProtect is developed by [PlayPro](https://github.com/PlayPro/CoreProtect). This plugin calls its public API and does not redistribute CoreProtect. See the [CoreProtect API documentation](https://github.com/PlayPro/CoreProtect/blob/master/docs/api/version/v10.md) and [Artistic License 2.0](https://github.com/PlayPro/CoreProtect/blob/master/LICENSE). CoreProtect is a separate project; this plugin is not affiliated with or endorsed by PlayPro.

## Install

1. Install CoreProtect on the Paper server.
2. Copy `CoreProtectExporter-1.0.1.jar` into the server's `plugins/` directory.
3. Restart the server.
4. Grant `coreprotectexport.use` to users allowed to export. The permission defaults to operators.

## Commands

In game, a radius lookup uses the command sender's current location:

```text
/coexport time:30d action:+block include:end_crystal radius:100
```

From the server console, provide a center player who is online:

```text
coexport time:30d action:+block include:end_crystal radius:100 center:PlayerName
```

Or provide explicit coordinates:

```text
coexport time:30d action:+block include:end_crystal radius:100 world:world x:0 y:64 z:0
```

Other examples:

```text
/coexport time:7d action:-block include:grass_block radius:100
/coexport time:12h action:block user:PlayerName split:25000
/coexport help
```

`action:+block` filters placements, `action:-block` filters breaks, and `action:block` includes both. Time is required and must be greater than zero. Common aliases are `t`, `a`, `i`, `e`, `u`, and `r` for time, action, include, exclude, user, and radius.

Each TSV has a header and metadata comments for the query, generation time, part number, and row counts. Columns are timestamp (UTC), player, action, object, world, coordinates, rollback status, and the raw CoreProtect API fields.

## Build

With Maven installed, run:

```text
mvn package
```

The built plugin will be written to `target/CoreProtectExporter-1.0.1.jar`.

## Source and license

This repository contains the independent exporter code only. No CoreProtect code or binary is included. The exporter source is provided without a separate license grant unless one is added by this repository's owner.
