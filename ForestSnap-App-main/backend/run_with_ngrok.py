"""
Run this to start Flask backend with a public ngrok URL.
Steps:
  1. pip install flask pyngrok
  2. Sign up at ngrok.com, get authtoken
  3. Run: ngrok config add-authtoken YOUR_TOKEN
  4. Run: python run_with_ngrok.py
  5. Copy the printed URL into Constants.kt as BACKEND_URL
"""
from pyngrok import ngrok
import threading
import time
from app import app


def run_flask():
    app.run(host='0.0.0.0', port=5000, debug=False, use_reloader=False)


if __name__ == '__main__':
    flask_thread = threading.Thread(target=run_flask, daemon=True)
    flask_thread.start()
    time.sleep(1)

    tunnel     = ngrok.connect(5000)
    public_url = tunnel.public_url

    print("\n" + "=" * 55)
    print(f"  Backend URL : {public_url}")
    print(f"  Health check: {public_url}/health")
    print(f"  Paste URL into Constants.kt as BACKEND_URL")
    print("=" * 55 + "\n")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("Shutting down.")
        ngrok.kill()
