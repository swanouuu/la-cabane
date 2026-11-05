// Sert l'aperçu du dragon chevauché en local : node serveur.mjs → http://127.0.0.1:8768/dragon.html
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".mjs": "text/javascript", ".png": "image/png" };
http.createServer((req, res) => {
  const nom = decodeURIComponent(new URL(req.url, "http://x").pathname).replace(/^\/+/, "") || "dragon.html";
  const fichier = path.join(ICI, nom);
  if (!fichier.startsWith(ICI) || !fs.existsSync(fichier) || fs.statSync(fichier).isDirectory()) {
    res.writeHead(404);
    res.end("introuvable");
    return;
  }
  res.writeHead(200, { "Content-Type": TYPES[path.extname(fichier)] ?? "application/octet-stream", "Cache-Control": "no-store" });
  fs.createReadStream(fichier).pipe(res);
}).listen(8768, "127.0.0.1", () => console.log("http://127.0.0.1:8768/dragon.html"));
