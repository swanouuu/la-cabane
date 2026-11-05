// Sert l'aperçu de l'ulti de la marque 3 en local : node serveur.mjs → http://127.0.0.1:8769/ulti/ulti.html
// La racine est docs/marque3 (la page lit aussi ../apercu/ : la peau et les outils de la marque).
// POST /enregistrer?nom=xxx.png écrit l'image reçue dans ulti/ (la planche faite par la page).
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ICI = path.dirname(fileURLToPath(import.meta.url));
const RACINE = path.dirname(ICI);
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".mjs": "text/javascript", ".png": "image/png", ".bin": "application/octet-stream" };
http.createServer((req, res) => {
  const url = new URL(req.url, "http://x");
  if (req.method === "POST" && url.pathname === "/enregistrer") {
    const nom = url.searchParams.get("nom") ?? "";
    if (!/^[\w-]+\.png$/.test(nom)) { res.writeHead(400); res.end("nom refusé"); return; }
    const morceaux = [];
    req.on("data", (m) => morceaux.push(m));
    req.on("end", () => {
      fs.writeFileSync(path.join(ICI, nom), Buffer.concat(morceaux));
      res.writeHead(200);
      res.end("ok");
    });
    return;
  }
  const nom = decodeURIComponent(url.pathname).replace(/^\/+/, "") || "ulti/ulti.html";
  const fichier = path.join(RACINE, nom);
  if (!fichier.startsWith(RACINE) || !fs.existsSync(fichier) || fs.statSync(fichier).isDirectory()) {
    res.writeHead(404);
    res.end("introuvable");
    return;
  }
  res.writeHead(200, { "Content-Type": TYPES[path.extname(fichier)] ?? "application/octet-stream", "Cache-Control": "no-store" });
  fs.createReadStream(fichier).pipe(res);
}).listen(8769, "127.0.0.1", () => console.log("http://127.0.0.1:8769/ulti/ulti.html"));
