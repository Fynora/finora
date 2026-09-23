#!/usr/bin/env python3
"""Generates ops/monitoring/grafana/dashboards/navigation-usage.json.

Written as a generator rather than hand-edited JSON because the panels are repetitive and the grid
coordinates have to stay consistent -- a hand-maintained 600-line JSON file is where an overlapping
gridPos or a copy-pasted query that still names the previous panel's metric comes from.

Run it and commit the output; the JSON is the artifact Grafana provisions, not this file.
"""

import json
from pathlib import Path

PROM = {"type": "prometheus", "uid": "prometheus"}
OUT = Path(__file__).resolve().parent.parent / (
    "ops/monitoring/grafana/dashboards/navigation-usage.json"
)

# Every panel that splits by a client-chosen dimension honours this, so "web only" and "mobile only"
# are one click rather than an edited query.
PLATFORM = '{platform=~"$platform"}'

panels = []
_y = 0


def row(title):
    global _y
    panels.append({"type": "row", "title": title,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": _y}})
    _y += 1


def panel(kind, title, description, w, x, h, targets, field_defaults=None, options=None, y_off=0):
    """y_off stacks a panel below an earlier one in the same column.

    Without it every panel in a section lands on that section's own y, which is right only for a
    single row of panels side by side -- two panels sharing a column silently overlap, and Grafana
    resolves that by relaying them somewhere unpredictable rather than by complaining.
    assert_no_overlap below is what stops that reaching a commit.
    """
    p = {
        "type": kind,
        "title": title,
        "description": description,
        "gridPos": {"h": h, "w": w, "x": x, "y": _y + y_off},
        "datasource": PROM,
        "targets": targets,
        "fieldConfig": {"defaults": field_defaults or {}, "overrides": []},
    }
    if options:
        p["options"] = options
    panels.append(p)


def advance(h):
    global _y
    _y += h


def stat(expr, legend=None, instant=True):
    t = {"expr": expr, "instant": instant}
    if legend:
        t["legendFormat"] = legend
    return t


STAT_OPTIONS = {"colorMode": "value", "graphMode": "none",
                "reduceOptions": {"calcs": ["lastNotNull"]}}

BAR_OPTIONS = {"displayMode": "gradient", "orientation": "horizontal",
               "reduceOptions": {"calcs": ["lastNotNull"]}}

# ---------------------------------------------------------------------------------------------
# The panels below this row are the measurement. This row is whether the measurement is happening
# at all, and it comes first deliberately -- see the dashboard description.
row("Is the baseline actually accumulating?")

panel(
    "stat", "Navigation events in window",
    "Total destination opens over the selected range. This panel exists because of how Micrometer "
    "behaves: a counter that has never been incremented is not exported as zero, it is not "
    "exported at all. So every other panel here renders identically for 'nobody navigated' and "
    "'the clients stopped reporting'. NO DATA ARRIVING means the series is absent entirely, which "
    "is a collection fault, not a quiet week.",
    6, 0, 6,
    [stat(f"sum(increase(finora_nav_destination_opened_total{PLATFORM}[$__range]))", "events")],
    {"unit": "short", "noValue": "NO DATA ARRIVING",
     "thresholds": {"mode": "absolute", "steps": [{"color": "blue", "value": None}]}},
    STAT_OPTIONS,
)

panel(
    "stat", "Destinations reporting",
    "How many distinct destinations have been seen in this window, out of the 19 in the shared "
    "taxonomy (NAV_TAXONOMY). A number well below 19 does not mean those screens are unused -- it "
    "more likely means they are not instrumented. Known untracked today: 'support' on both "
    "clients. Treat this as coverage, not popularity.",
    6, 6, 6,
    [stat(f"count(count by (destination) "
          f"(increase(finora_nav_destination_opened_total{PLATFORM}[$__range]) > 0))", "seen")],
    {"unit": "short", "noValue": "0",
     "thresholds": {"mode": "absolute",
                    "steps": [{"color": "red", "value": None},
                              {"color": "orange", "value": 8},
                              {"color": "green", "value": 15}]}},
    STAT_OPTIONS,
)

panel(
    "stat", "Platforms reporting",
    "Distinct values of the platform tag seen in this window: web, mobile_ios, mobile_android. "
    "Fewer than the clients you have shipped means one of them is not reporting, and a baseline "
    "missing a whole platform cannot answer the question this dashboard exists for.",
    6, 12, 6,
    [stat(f"count(count by (platform) "
          f"(increase(finora_nav_destination_opened_total[$__range]) > 0))", "platforms")],
    {"unit": "short", "noValue": "0",
     "thresholds": {"mode": "absolute",
                    "steps": [{"color": "red", "value": None},
                              {"color": "orange", "value": 2},
                              {"color": "green", "value": 3}]}},
    STAT_OPTIONS,
)

panel(
    "stat", "Backend scrapeable",
    "Whether Prometheus can reach the backend's management port at all. Distinguishes 'nothing is "
    "arriving because the clients are silent' from 'nothing is arriving because the scrape is "
    "down'. The two look identical on every other panel.",
    6, 18, 6,
    [stat('up{job="finora-backend"}', "up")],
    {"unit": "short", "noValue": "NO TARGET",
     "mappings": [{"type": "value", "options": {"0": {"text": "DOWN", "color": "red", "index": 0},
                                                "1": {"text": "UP", "color": "green", "index": 1}}}],
     "thresholds": {"mode": "absolute", "steps": [{"color": "text", "value": None}]}},
    STAT_OPTIONS,
)
advance(6)

# ---------------------------------------------------------------------------------------------
row("Where people go")

panel(
    "bargauge", "Destination opens",
    "The core of the baseline: the usage distribution across destinations, before the shared "
    "taxonomy changes how they are grouped. This is the ranking an after-period gets compared "
    "against, and there is no backfill -- see the navigation-analytics spec, Release dependencies.",
    12, 0, 13,
    [stat(f"sort_desc(sum by (destination) "
          f"(increase(finora_nav_destination_opened_total{PLATFORM}[$__range])))", "{{destination}}")],
    {"unit": "short", "min": 0,
     "thresholds": {"mode": "absolute", "steps": [{"color": "blue", "value": None}]}},
    BAR_OPTIONS,
)

panel(
    "bargauge", "Opens by taxonomy group",
    "The same events rolled up to the five groups plus ungrouped root. The group tag is derived "
    "from the shared taxonomy at the point of tracking, so this is the grouping the clients "
    "already agree on -- not a reinterpretation applied in this query.",
    12, 12, 7,
    [stat(f"sort_desc(sum by (group) "
          f"(increase(finora_nav_destination_opened_total{PLATFORM}[$__range])))", "{{group}}")],
    {"unit": "short", "min": 0,
     "thresholds": {"mode": "absolute", "steps": [{"color": "purple", "value": None}]}},
    BAR_OPTIONS,
)

panel(
    "timeseries", "Opens per rolling 24h, by group",
    "Whether the observation window has actually covered what it needs to. The baseline must span "
    "at least four weeks INCLUDING one complete month-end, because this product's dominant usage "
    "cycle is statement import and it clusters at month end. A month-end spike should be visible "
    "here before the window is called complete.\n\n"
    "Each point is the total over the PRECEDING 24 hours, not a calendar day -- so the series is "
    "smooth and timezone-independent, but a single point is not 'Tuesday\'s opens' and should not "
    "be read as one. Adjacent points overlap heavily by construction.",
    12, 12, 6,
    [stat(f"sum by (group) (increase(finora_nav_destination_opened_total{PLATFORM}[1d]))",
          "{{group}}", instant=False)],
    {"unit": "short", "custom": {"fillOpacity": 10, "stacking": {"mode": "normal"}}},
    y_off=7,  # below "Opens by taxonomy group", which is h=7 in this same column
)
advance(13)

# ---------------------------------------------------------------------------------------------
row("How they get there")

panel(
    "bargauge", "Entry points used",
    "Which affordance carried people to a destination. This is the series the taxonomy work should "
    "move most: regrouping the navigation changes how a destination is reached far more than "
    "whether it is reached.\n\n"
    "Two values are expected to read zero today and that is a known instrumentation gap rather "
    "than a finding: 'contextual' and 'search' are defined in NavEntryPoint but never emitted by "
    "either client.",
    12, 0, 8,
    [stat(f"sort_desc(sum by (entry) "
          f"(increase(finora_nav_entry_point_used_total{PLATFORM}[$__range])))", "{{entry}}")],
    {"unit": "short", "min": 0,
     "thresholds": {"mode": "absolute", "steps": [{"color": "green", "value": None}]}},
    BAR_OPTIONS,
)

panel(
    "timeseries", "Entry point mix over time",
    "The same breakdown as a rate, so a shift in how people navigate is visible as it happens "
    "rather than only in a window total. A step change here with no deploy behind it is worth "
    "understanding before the taxonomy work starts.",
    12, 12, 8,
    [stat(f"sum by (entry) (rate(finora_nav_entry_point_used_total{PLATFORM}[1h]))",
          "{{entry}}", instant=False)],
    {"unit": "ops", "custom": {"fillOpacity": 10}},
)
advance(8)

# ---------------------------------------------------------------------------------------------
row("Web versus mobile")

panel(
    "bargauge", "Destination opens by platform",
    "Whether the two clients are actually used differently -- the question the platform tag exists "
    "to answer, and the one that decides whether a single shared taxonomy is the right call.\n\n"
    "One caveat on reading it: X-Client-Platform is client-asserted, and ClientIdentity resolves an "
    "absent or unrecognised header to 'web' by design. So 'web' carries any unheadered traffic.",
    16, 0, 10,
    [stat("sort_desc(sum by (platform, destination) "
          "(increase(finora_nav_destination_opened_total[$__range])))",
          "{{platform}} — {{destination}}")],
    {"unit": "short", "min": 0,
     "thresholds": {"mode": "absolute", "steps": [{"color": "blue", "value": None}]}},
    BAR_OPTIONS,
)

panel(
    "stat", "Navigation searches",
    "How often navigation search was used. Counts only that a search happened -- never what was "
    "typed. A search term is free text a user wrote, which is the sharpest case of what must never "
    "leave the platform (observability.md §3), so there is deliberately no term breakdown to add "
    "here later.",
    8, 16, 5,
    [stat(f"sum(increase(finora_nav_search_used_total{PLATFORM}[$__range]))", "searches")],
    {"unit": "short", "noValue": "0",
     "thresholds": {"mode": "absolute", "steps": [{"color": "yellow", "value": None}]}},
    STAT_OPTIONS,
)

panel(
    "timeseries", "Search rate by platform",
    "Rising search use is the signal that the navigation is not surfacing what people want "
    "directly -- the clearest single argument the taxonomy work could be given, and the clearest "
    "way to tell afterwards whether it worked.",
    8, 16, 5,
    [stat("sum by (platform) (rate(finora_nav_search_used_total[1h]))",
          "{{platform}}", instant=False)],
    {"unit": "ops", "custom": {"fillOpacity": 10}},
    y_off=5,  # below "Navigation searches", which is h=5 in this same column
)
advance(10)

dashboard = {
    "uid": "finora-navigation-usage",
    "title": "Finora — Navigation usage",
    "tags": ["finora", "navigation", "analytics"],
    "timezone": "browser",
    "schemaVersion": 39,
    # Thirty days, not the six hours the other dashboards default to. This one is read to decide
    # whether a four-week baseline including a month-end has accumulated; a six-hour window would
    # answer a question nobody is asking here.
    "time": {"from": "now-30d", "to": "now"},
    # Likewise the refresh: nothing on a thirty-day window changes meaningfully in thirty seconds,
    # and each refresh is a range query across the whole window.
    "refresh": "15m",
    "description": (
        "The three navigation counters from NavigationMetrics, collected to establish a usage "
        "baseline BEFORE the shared navigation taxonomy changes how destinations are grouped. "
        "There is no backfill: once the taxonomy ships the old distribution is gone permanently, "
        "which is why this dashboard is a release gate for that work rather than a nice-to-have "
        "(see docs/superpowers/specs/2026-09-23-navigation-analytics-design.md, Release "
        "dependencies).\n\n"
        "It is a measurement dashboard, not an alerting one. There is no good or bad value for any "
        "series here — the point is to know the shape of current usage well enough to tell whether "
        "the redesign changed it.\n\n"
        "Everything here is aggregate and non-identifying by construction: no user, account, "
        "session or device identifier, and no free text. That is the basis on which it is "
        "collected without a consent prompt, so it is a constraint to preserve rather than a "
        "property of the current queries."
    ),
    "templating": {
        "list": [
            {
                "name": "platform",
                "label": "Platform",
                "type": "query",
                "datasource": PROM,
                "query": "label_values(finora_nav_destination_opened_total, platform)",
                "refresh": 2,
                "includeAll": True,
                "multi": True,
                "current": {"text": "All", "value": "$__all"},
            }
        ]
    },
    "panels": panels,
}

def assert_no_overlap(all_panels):
    """Two panels claiming the same grid cell is the defect this generator exists to prevent.

    Grafana does not reject an overlapping layout, it silently relays it -- so the dashboard still
    provisions, still renders, and quietly shows panels somewhere other than where this file says
    they are. Caught here, at build time, where the coordinates actually are.
    """
    occupied = {}
    for p in all_panels:
        g = p["gridPos"]
        for y in range(g["y"], g["y"] + g["h"]):
            for x in range(g["x"], g["x"] + g["w"]):
                if (x, y) in occupied:
                    raise SystemExit(
                        f"gridPos overlap at ({x}, {y}): "
                        f"{occupied[(x, y)]!r} and {p['title']!r}")
                occupied[(x, y)] = p["title"]


assert_no_overlap(panels)
OUT.write_text(json.dumps(dashboard, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(f"Wrote {OUT.relative_to(Path(__file__).resolve().parent.parent)}: "
      f"{len([p for p in panels if p['type'] != 'row'])} panels in "
      f"{len([p for p in panels if p['type'] == 'row'])} rows.")
