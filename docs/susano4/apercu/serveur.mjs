// Sert l'aperçu du Susano parfait (susano.html et susano.bin) en local : node serveur.mjs → http://127.0.0.1:8767/susano.html
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const TYPES = { ".html": "text/html; charset=utf-8", ".mjs": "text/javascript", ".js": "text/javascript", ".bin": "application/octet-stream", ".png": "image/png" };
http.createServer((req, res) => {
  const nom = decodeURIComponent(new URL(req.url, "http://x").pathname).replace(/^\/+/, "") || "susano.html";
  const fichier = path.join(ICI, nom);
  if (!fichier.startsWith(ICI) || !fs.existsSync(fichier) || fs.statSync(fichier).isDirectory()) {
    res.writeHead(404);
    res.end("introuvable");
    return;
  }
  res.writeHead(200, { "Content-Type": TYPES[path.extname(fichier)] ?? "application/octet-stream", "Cache-Control": "no-store" });
  fs.createReadStream(fichier).pipe(res);
}).listen(8767, "127.0.0.1", () => console.log("http://127.0.0.1:8767/susano.html"));
