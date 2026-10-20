import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const root = new URL('./', import.meta.url);
const files = {'/':'index.html','/index.html':'index.html','/app.js':'app.js','/styles.css':'styles.css'};
const types = {html:'text/html; charset=utf-8',js:'text/javascript; charset=utf-8',css:'text/css; charset=utf-8'};
http.createServer(async (req,res)=>{
  const path = new URL(req.url,'http://localhost').pathname;
  if(path.startsWith('/api/')) {
    const upstream=http.request({hostname:'127.0.0.1',port:process.env.API_PORT || 8080,path:req.url,method:req.method,headers:{...req.headers,host:'localhost:8080'}},r=>{
      res.writeHead(r.statusCode,r.headers);r.pipe(res);
    });
    upstream.on('error',()=>{res.writeHead(502,{'Content-Type':'application/json'});res.end(JSON.stringify({message:'API недоступен. Запустите бэкенд на порту 8080'}));});
    req.pipe(upstream);return;
  }
  if(!files[path]) {res.writeHead(404);res.end();return;}
  try {
    const data=await readFile(fileURLToPath(new URL(files[path],root)));
    res.writeHead(200,{'Content-Type':types[files[path].split('.').pop()],'X-Content-Type-Options':'nosniff','Content-Security-Policy':"default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'"});res.end(data);
  } catch {res.writeHead(500);res.end();}
}).listen(5173,'127.0.0.1',()=>console.log('BugunAL: http://localhost:5173'));
