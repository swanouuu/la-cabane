// Sert le croquis 3D de la Cave à Haru en local : node serveur.mjs → http://127.0.0.1:8793/cave.html
// PUT /affiches/<nom>.png enregistre une affiche dessinée par la page dans docs/cave-haru/affiches/.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const TYPES = { ".html": "text/html; charset=utf-8", ".mjs": "text/javascript", ".js": "text/javascript", ".png": "image/png", ".json": "application/json" };
http.createServer((req, res) => {
  const nom = decodeURIComponent(new URL(req.url, "http://x").pathname).replace(/^\/+/, "") || "cave.html";
  if (req.method === "PUT" && /^affiches\/[a-z0-9-]+\.png$/.test(nom)) {
    const morceaux = [];
    req.on("data", (m) => morceaux.push(m));
    req.on("end", () => {
      fs.mkdirSync(path.join(ICI, "affiches"), { recursive: true });
      fs.writeFileSync(path.join(ICI, nom), Buffer.concat(morceaux));
      res.writeHead(200);
      res.end("ok");
    });
    return;
  }
  const fichier = path.join(ICI, nom);
  if (!fichier.startsWith(ICI) || !fs.existsSync(fichier) || fs.statSync(fichier).isDirectory()) {
    res.writeHead(404);
    res.end("introuvable");
    return;
  }
  res.writeHead(200, { "Content-Type": TYPES[path.extname(fichier)] ?? "application/octet-stream", "Cache-Control": "no-store" });
  fs.createReadStream(fichier).pipe(res);
}).listen(8793, "127.0.0.1", () => console.log("http://127.0.0.1:8793/cave.html"));
