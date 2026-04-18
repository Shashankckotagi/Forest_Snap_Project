from flask import Flask, request, jsonify
import time

app = Flask(__name__)

# In-memory store: zone_id -> list of submission dicts
zone_data = {}


@app.route('/health', methods=['GET'])
def health():
    return jsonify({'status': 'ok', 'zones': len(zone_data)})


@app.route('/submit', methods=['POST'])
def submit():
    """
    Accepts a scored photo submission from the Android app.
    Body JSON:
      zone_id:        str    — grid cell identifier computed from GPS
      risk_score:     float  — 0.0 to 1.0 from on-device TFLite model
      gps_accuracy:   float  — metres, lower is better
      timestamp:      int    — epoch milliseconds from Android
      contributor_id: str    — Android device ID
    Returns: zone_id, zone_score, confidence, contributors
    """
    data = request.get_json()
    if not data:
        return jsonify({'error': 'no json body'}), 400

    zone_id = data.get('zone_id')
    if not zone_id:
        return jsonify({'error': 'zone_id required'}), 400

    if zone_id not in zone_data:
        zone_data[zone_id] = []

    zone_data[zone_id].append({
        'risk_score':     float(data.get('risk_score', 0.5)),
        'gps_accuracy':   float(data.get('gps_accuracy', 10.0)),
        'timestamp':      int(data.get('timestamp', time.time() * 1000)),
        'contributor_id': str(data.get('contributor_id', 'unknown'))
    })

    result = calculate_trust_score(zone_data[zone_id])
    return jsonify({
        'zone_id':      zone_id,
        'zone_score':   result['score'],
        'confidence':   result['confidence'],
        'contributors': len(zone_data[zone_id])
    })


@app.route('/zones', methods=['GET'])
def get_zones():
    """Returns trust-weighted score for every zone that has data."""
    result = {}
    for zone_id, submissions in zone_data.items():
        scored = calculate_trust_score(submissions)
        result[zone_id] = {
            'score':        scored['score'],
            'confidence':   scored['confidence'],
            'contributors': len(submissions)
        }
    return jsonify(result)


@app.route('/spread/<zone_id>', methods=['GET'])
def get_spread(zone_id):
    """
    Given a zone, returns spread probability to each neighbor zone.
    Spread probability = neighbor_zone_score * 0.85
    (fuel load is primary determinant of spread)
    """
    neighbors = get_neighbors(zone_id)
    spread = {}
    for n in neighbors:
        if n in zone_data:
            score = calculate_trust_score(zone_data[n])['score']
            spread[n] = round(score * 0.85, 2)
        else:
            spread[n] = 0.10   # unknown zone = low default spread risk
    return jsonify({'source_zone': zone_id, 'spread': spread})


@app.route('/reset', methods=['POST'])
def reset():
    """Clears all zone data. For testing only."""
    zone_data.clear()
    return jsonify({'status': 'cleared'})


def calculate_trust_score(submissions):
    """
    Patent Claim 2 — GPS-accuracy-weighted multi-contributor trust formula.

    Each submission is weighted by:
      GPS weight:  perfect 3m = 1.0, poor 20m = 0.0  (linear)
      Time weight: fresh 0h  = 1.0, old  72h = 0.3  (linear decay)

    Zone score  = sum(risk_score * weight) / sum(weight)
    Confidence  = min(0.99, 0.5 + contributors * 0.1)
    """
    total_weight   = 0.0
    weighted_score = 0.0
    now_ms         = time.time() * 1000

    for s in submissions:
        gps_accuracy = s.get('gps_accuracy', 10.0)
        timestamp_ms = s.get('timestamp', now_ms)
        risk_score   = s.get('risk_score', 0.5)

        # GPS weight: 3m -> 1.0, 20m+ -> 0.0
        gps_weight  = max(0.0, 1.0 - ((gps_accuracy - 3.0) / 17.0))

        # Time weight: 0h -> 1.0, 72h -> 0.3
        age_hours   = (now_ms - timestamp_ms) / (1000 * 3600)
        time_weight = max(0.3, 1.0 - (age_hours / 72.0))

        weight          = gps_weight * time_weight
        weighted_score += risk_score * weight
        total_weight   += weight

    final_score = (weighted_score / total_weight) if total_weight > 0 else 0.0
    confidence  = min(0.99, 0.5 + len(submissions) * 0.1)

    return {
        'score':      round(final_score, 3),
        'confidence': round(confidence, 3)
    }


def get_neighbors(zone_id):
    """
    Zone IDs are "row_col" strings e.g. "12_76".
    Neighbors are the 4 adjacent grid cells (N, S, E, W).
    Grid formula: row = int((lat - 12.0) * 100), col = int((lon - 76.0) * 100)
    Each cell is approximately 1km x 1km around the Bandipur/Bangalore region.
    Adjust the 12.0 and 76.0 offsets for other regions.
    """
    try:
        parts = zone_id.split('_')
        r, c  = int(parts[0]), int(parts[1])
        return [f"{r-1}_{c}", f"{r+1}_{c}", f"{r}_{c-1}", f"{r}_{c+1}"]
    except (ValueError, IndexError):
        return []


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)
