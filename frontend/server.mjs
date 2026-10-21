// Development compatibility: Spring Boot serves HTML, assets and APIs.
import http from 'node:http';
const port=process.env.API_PORT||8080;
http.createServer((req,res)=>{
  const upstream=http.request({hostname:'127.0.0.1',port,path:req.url,method:req.method,headers:{...req.headers,host:'localhost:'+port}},response=>{res.writeHead(response.statusCode,response.headers);response.pipe(res);});
  upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502,{'Content-Type':'text/plain; charset=utf-8'});res.end('BugunAL: start Spring Boot on port '+port);});
  req.pipe(upstream);
}).listen(5173,'127.0.0.1',()=>console.log('BugunAL -> Spring Boot: http://localhost:5173'));