// Sert les aperçus 3D des lames en local : node serveur.mjs → http://127.0.0.1:8794/combos.html
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".json": "application/json", ".png": "image/png" };
http.createServer((req, res) => {
  const nom = decodeURIComponent(new URL(req.url, "http://x").pathname).replace(/^\/+/, "") || "combos.html";
  if (req.method === "PUT" && /^captures\/[a-z0-9_-]+\.png$/.test(nom)) {
    const morceaux = [];
    req.on("data", (m) => morceaux.push(m));
    req.on("end", () => {
      fs.mkdirSync(path.join(ICI, "captures"), { recursive: true });
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
}).listen(8794, "127.0.0.1", () => console.log("http://127.0.0.1:8794/combos.html"));
