# StreamNode Server — Free Deployment Guide (TypeScript v5.2)
# Subdomain: streamnode.visioncoachinginstitute.online → Render.com

## WHY NOT VERCEL?
Vercel is serverless — it cannot run WebSocket servers or setInterval.
This server needs persistent connections. Use Render.com (also 100% free).

---

## STEP 1 — Push server folder to GitHub

Create a NEW GitHub repo (public or private) just for the server:

  git init
  git remote add origin https://github.com/YOUR_USERNAME/streamnode-server.git
  git add .
  git commit -m "StreamNode server v5"
  git push -u origin main

IMPORTANT: Push only the /server folder contents, NOT the Android project root.
Navigate into the server folder first:

  cd "C:\Users\lapto\StudioProjects\StreamNode - Copy\server"
  git init
  git remote add origin https://github.com/YOUR_USERNAME/streamnode-server.git

---

## STEP 2 — Deploy on Render.com (FREE)

1. Go to https://render.com → Sign up free (use GitHub login)
2. Click "New +" → "Web Service"
3. Connect your GitHub repo (streamnode-server)
4. Render auto-detects render.yaml — accept all settings
5. Under "Environment Variables", add:
     FIREBASE_DB_SECRET = your_secret_here
6. Click "Create Web Service"
7. Wait ~3 minutes → you get a URL like:
     https://streamnode-server.onrender.com

Test it: open https://streamnode-server.onrender.com/health
Should return: {"status":"ok"} or similar

---

## STEP 3 — Create subdomain on Hostinger DNS

1. Login to hpanel.hostinger.com
2. Go to: Domains → visioncoachinginstitute.online → DNS / Nameservers
3. Click "Add Record" and add:

   Type:  CNAME
   Name:  streamnode
   Value: streamnode-server.onrender.com
   TTL:   3600

4. Save. DNS propagates in 5–30 minutes.

---

## STEP 4 — Add custom domain on Render

1. In Render dashboard → your service → "Settings" → "Custom Domains"
2. Click "Add Custom Domain"
3. Enter: streamnode.visioncoachinginstitute.online
4. Render auto-provisions a free SSL certificate (Let's Encrypt)
5. Wait 10–15 min for SSL to activate

---

## STEP 5 — Update Android app server URL

In your Android app, change the server base URL from localhost to:
  wss://streamnode.visioncoachinginstitute.online

Search for: "ws://" or "localhost" or "10.0.2.2" in your app source
Replace with the wss:// URL above.

---

## IMPORTANT — Free tier sleep behaviour

Render free tier spins down after 15 minutes of inactivity.
First connection after sleep takes ~30 seconds to wake up.

To keep it alive (optional, still free):
Use https://uptimerobot.com — add a monitor for:
  https://streamnode.visioncoachinginstitute.online/health
Set check interval: every 14 minutes → server never sleeps.

---

## FINAL URLS

  Dashboard : https://streamnode.visioncoachinginstitute.online
  Health    : https://streamnode.visioncoachinginstitute.online/health
  Streams   : https://streamnode.visioncoachinginstitute.online/streams
  WebSocket : wss://streamnode.visioncoachinginstitute.online/stream
  Control   : wss://streamnode.visioncoachinginstitute.online/control
  Listen    : wss://streamnode.visioncoachinginstitute.online/listen
