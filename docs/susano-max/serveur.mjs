// Sert le croquis du Susano niveau 3 final : node serveur.mjs → http://127.0.0.1:8799/
// (sert tout docs/ pour que la page trouve les planches des autres dossiers)
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const docs = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const types = { ".html": "text/html; charset=utf-8", ".png": "image/png", ".jpg": "image/jpeg", ".gif": "image/gif" };
http.createServer((req, res) => {
  let url = decodeURIComponent(req.url.split("?")[0]);
  if (url === "/" || url === "/susano-max/") { res.writeHead(302, { Location: "/susano-max/index.html" }).end(); return; }
  const fichier = path.resolve(docs, "." + url);
  if (!fichier.startsWith(docs) || !fs.existsSync(fichier) || !fs.statSync(fichier).isFile()) {
    res.writeHead(404).end();
    return;
  }
  res.writeHead(200, { "Content-Type": types[path.extname(fichier)] || "application/octet-stream", "Cache-Control": "no-store" });
  fs.createReadStream(fichier).pipe(res);
}).listen(8799, "127.0.0.1", () => console.log("http://127.0.0.1:8799/"));
