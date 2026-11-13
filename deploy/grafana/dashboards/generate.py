#!/usr/bin/env python3
"""Dashboards as code: generates the provisioned Grafana dashboards (docs/10-observability.md §3).

Run after editing:  python3 deploy/grafana/dashboards/generate.py
Conventions: headline numbers are stat tiles; time series have one y-axis, 2px lines and a legend when
there are two or more series; series colors follow the series name (never its rank); status thresholds use
the reserved good/warning/critical colors and always show the value as text.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
DS = {"type": "prometheus", "uid": "prometheus"}
GOOD, WARN, CRIT = "#0ca30c", "#fab219", "#d03b3b"


class Dash:
    def __init__(self, uid, title, description):
        self.uid, self.title, self.description = uid, title, description
        self.panels, self.y, self.x, self.row_h, self.next_id = [], 0, 0, 0, 1

    def _place(self, w, h):
        if self.x + w > 24:
            self.y += self.row_h
            self.x, self.row_h = 0, 0
        pos = {"x": self.x, "y": self.y, "w": w, "h": h}
        self.x += w
        self.row_h = max(self.row_h, h)
        return pos

    def row(self, title):
        if self.x:
            self.y += self.row_h
            self.x, self.row_h = 0, 0
        self.panels.append({"type": "row", "title": title, "id": self._id(), "collapsed": False,
                            "gridPos": {"x": 0, "y": self.y, "w": 24, "h": 1}})
        self.y += 1

    def _id(self):
        self.next_id += 1
        return self.next_id

    def stat(self, title, expr, unit="short", thresholds=None, w=6, h=4, description="", decimals=None):
        steps = thresholds or [{"color": "text", "value": None}]
        self.panels.append({
            "type": "stat", "title": title, "description": description, "id": self._id(), "datasource": DS,
            "gridPos": self._place(w, h),
            "targets": [{"refId": "A", "expr": expr, "instant": True, "datasource": DS}],
            "options": {"reduceOptions": {"calcs": ["lastNotNull"], "values": False, "fields": ""},
                        "colorMode": "value" if thresholds else "none", "graphMode": "none",
                        "textMode": "value", "justifyMode": "auto"},
            "fieldConfig": {"defaults": {"unit": unit, "decimals": decimals, "noValue": "0",
                                         "thresholds": {"mode": "absolute", "steps": steps}},
                            "overrides": []}})

    def series(self, title, targets, unit="short", w=12, h=8, description="", stack=False, min0=True):
        multi = len(targets) > 1 or any("{{" in t[1] for t in targets)
        self.panels.append({
            "type": "timeseries", "title": title, "description": description, "id": self._id(), "datasource": DS,
            "gridPos": self._place(w, h),
            "targets": [{"refId": chr(65 + i), "expr": e, "legendFormat": legend, "datasource": DS}
                        for i, (e, legend) in enumerate(targets)],
            "options": {"legend": {"showLegend": multi, "displayMode": "list", "placement": "bottom"},
                        "tooltip": {"mode": "multi", "sort": "desc"}},
            "fieldConfig": {"defaults": {
                "unit": unit, "min": 0 if min0 else None,
                "color": {"mode": "palette-classic-by-name"},
                "custom": {"lineWidth": 2, "fillOpacity": 10 if stack else 0, "pointSize": 8, "showPoints": "never",
                           "drawStyle": "line", "spanNulls": True,
                           "stacking": {"mode": "normal" if stack else "none", "group": "A"},
                           "axisSoftMin": 0}},
                "overrides": []}})

    def json(self):
        return {"uid": self.uid, "title": self.title, "description": self.description, "tags": ["hpg"],
                "schemaVersion": 39, "version": 1, "editable": True, "refresh": "30s", "timezone": "browser",
                "time": {"from": "now-6h", "to": "now"}, "panels": self.panels, "templating": {"list": []},
                "annotations": {"list": []}}


def zero_good():
    return [{"color": GOOD, "value": None}, {"color": CRIT, "value": 1}]


# 1. Payments overview -------------------------------------------------------------------------
d = Dash("hpg-payments", "HPG · Payments overview",
         "Creation and capture rates, outcomes, latency and stuck payments.")
d.row("Now")
d.stat("Payments created (1h)", "sum(increase(payments_initiated_total[1h]))", decimals=0)
d.stat("Capture success (1h)",
       'sum(increase(payments_terminal_total{status="CAPTURED"}[1h])) / clamp_min(sum(increase(payments_terminal_total[1h])), 1)',
       unit="percentunit", decimals=1,
       thresholds=[{"color": CRIT, "value": None}, {"color": WARN, "value": 0.7}, {"color": GOOD, "value": 0.9}],
       description="Captured / all payments that reached a final status. Alert below 70%.")
d.stat("Stuck > 15 min", "max(payments_stuck)", decimals=0, thresholds=zero_good(),
       description="PROCESSING/UNKNOWN for more than 15 minutes.")
d.stat("Late captures (24h)", "sum(increase(payments_late_capture_total[24h]))", decimals=0,
       thresholds=zero_good(), description="Money captured after the payment had expired, failed or been cancelled.")
d.row("Flow")
d.series("Payments created per minute", [("sum by (capture_mode) (rate(payments_initiated_total[5m])) * 60", "{{capture_mode}}")])
d.series("Final outcomes per minute", [("sum by (status) (rate(payments_terminal_total[5m])) * 60", "{{status}}")])
d.series("Captured amount per minute (INR)",
         [('sum(rate(payment_amount_captured_sum{currency="INR"}[5m])) * 60', "captured")], unit="currencyINR")
d.series("API latency p95 by service", [(
    'histogram_quantile(0.95, sum by (le, application) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[5m])))',
    "{{application}}")], unit="s")
d.series("Server errors (5xx) per minute by service", [(
    'sum by (application) (rate(http_server_requests_seconds_count{status=~"5.."}[5m])) * 60', "{{application}}")])
d.series("Refunds by outcome per minute", [(
    'sum by (to) (rate(refunds_transitions_total{to=~"COMPLETED|FAILED|UNKNOWN"}[5m])) * 60', "{{to}}")])

# 2. Provider health ---------------------------------------------------------------------------
p = Dash("hpg-provider", "HPG · Provider health", "Payment provider latency, outcomes and circuit breaker.")
p.row("Now")
p.stat("Circuit breaker open", 'max(resilience4j_circuitbreaker_state{state=~"open|forced_open"}) or vector(0)',
       decimals=0, thresholds=zero_good(), description="1 = calls to the provider are being short-circuited.")
p.stat("Provider errors (15m)",
       'sum(increase(provider_requests_seconds_count{outcome!="success"}[15m])) or vector(0)', decimals=0,
       thresholds=[{"color": GOOD, "value": None}, {"color": WARN, "value": 1}, {"color": CRIT, "value": 20}])
p.stat("Webhook signature failures (24h)", "sum(increase(webhooks_signature_failures_total[24h])) or vector(0)",
       decimals=0, thresholds=[{"color": GOOD, "value": None}, {"color": WARN, "value": 1}])
p.stat("Provider p95 (15m)",
       "histogram_quantile(0.95, sum by (le) (rate(provider_requests_seconds_bucket[15m])))", unit="s", decimals=2)
p.row("Calls")
p.series("Provider latency p95 by operation", [(
    "histogram_quantile(0.95, sum by (le, operation) (rate(provider_requests_seconds_bucket[5m])))", "{{operation}}")],
    unit="s")
p.series("Provider calls by outcome per minute", [(
    "sum by (outcome) (rate(provider_requests_seconds_count[5m])) * 60", "{{outcome}}")])
p.series("Circuit breaker failure rate", [("max by (name) (resilience4j_circuitbreaker_failure_rate)", "{{name}}")],
         unit="percent")
p.series("Webhooks received per minute", [("sum by (provider) (rate(webhooks_received_total[5m])) * 60", "{{provider}}")])

# 3. Event pipeline ----------------------------------------------------------------------------
e = Dash("hpg-events", "HPG · Event pipeline", "Outbox backlog, Kafka consumer lag, dead letters and notifications.")
e.row("Now")
e.stat("Unpublished events", "sum(outbox_pending_events)", decimals=0,
       thresholds=[{"color": GOOD, "value": None}, {"color": WARN, "value": 100}, {"color": CRIT, "value": 1000}])
e.stat("Oldest unpublished", "max(outbox_oldest_pending_age_seconds)", unit="s", decimals=0,
       thresholds=[{"color": GOOD, "value": None}, {"color": WARN, "value": 30}, {"color": CRIT, "value": 120}])
e.stat("Dead-lettered (24h)", "sum(increase(kafka_dlt_records_total[24h])) or vector(0)", decimals=0,
       thresholds=zero_good())
e.stat("Max consumer lag", "max(kafka_consumer_fetch_manager_records_lag_max) or vector(0)", decimals=0,
       thresholds=[{"color": GOOD, "value": None}, {"color": WARN, "value": 100}, {"color": CRIT, "value": 1000}])
e.row("Flow")
e.series("Outbox backlog by service", [("max by (application) (outbox_pending_events)", "{{application}}")])
e.series("Consumer lag by service", [(
    "max by (application) (kafka_consumer_fetch_manager_records_lag_max)", "{{application}}")])
e.series("Dead-lettered records per minute by topic", [("sum by (topic) (rate(kafka_dlt_records_total[5m])) * 60", "{{topic}}")])
e.series("Notification deliveries per minute by outcome", [(
    "sum by (outcome) (rate(notifications_attempts_total[5m])) * 60", "{{outcome}}")])

# 4. Reconciliation & ledger -------------------------------------------------------------------
r = Dash("hpg-recon-ledger", "HPG · Reconciliation & ledger", "Open discrepancies, last run, ledger integrity.")
r.row("Now")
r.stat("Ledger integrity violations", "max(ledger_integrity_violations)", decimals=0, thresholds=zero_good())
r.stat("Open discrepancies", "max(recon_open_discrepancies)", decimals=0,
       thresholds=[{"color": GOOD, "value": None}, {"color": WARN, "value": 1}])
r.stat("Since last reconciliation", "time() - max(recon_last_successful_run_timestamp > 0)", unit="s", decimals=0,
       thresholds=[{"color": GOOD, "value": None}, {"color": CRIT, "value": 93600}],
       description="Alert after 26 hours.")
r.stat("Ledger postings (24h)", "sum(increase(ledger_postings_total[24h]))", decimals=0)
r.row("History")
r.series("Reconciliation results per run", [("sum by (status) (increase(recon_records_total[1h]))", "{{status}}")])
r.series("Ledger postings per minute by type", [("sum by (type) (rate(ledger_postings_total[5m])) * 60", "{{type}}")])

for dash in (d, p, e, r):
    with open(os.path.join(HERE, dash.uid + ".json"), "w") as f:
        json.dump(dash.json(), f, indent=2)
        f.write("\n")
    print("wrote", dash.uid + ".json")
