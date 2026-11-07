import http from "node:http";
import crypto from "node:crypto";

const port = Number(process.env.PORT || 8793);
const upstream = (process.env.AI_BASE_URL || "https://api.zagon.fun/v1").replace(/\/$/, "");
const apiKey = process.env.AI_API_KEY || "";
const bootstrap = process.env.APP_BOOTSTRAP_SECRET || "";
const model = process.env.AI_MODEL || "deepseek-v4-pro";
const installs = new Map();
const nonces = new Map();
const buckets = new Map();

function json(res, status, body) {
  const bytes = Buffer.from(JSON.stringify(body));
  res.writeHead(status, {"content-type":"application/json; charset=utf-8","content-length":bytes.length,"cache-control":"no-store","x-content-type-options":"nosniff"});
  res.end(bytes);
}
function hmac(key, value) { return crypto.createHmac("sha256", key).update(value).digest("hex"); }
function equal(a,b) { try { return crypto.timingSafeEqual(Buffer.from(a,"hex"),Buffer.from(b,"hex")); } catch { return false; } }
function read(req) { return new Promise((resolve,reject)=>{ const chunks=[]; let size=0; req.on("data",c=>{size+=c.length;if(size>1_500_000){reject(Error("too_large"));req.destroy();}else chunks.push(c)}); req.on("end",()=>resolve(Buffer.concat(chunks))); req.on("error",reject); }); }
function limited(key, max=30) { const now=Date.now(), item=buckets.get(key)||{at:now,n:0}; if(now-item.at>60_000){item.at=now;item.n=0} item.n++;buckets.set(key,item);return item.n>max; }
function auth(req, body) {
  const install=req.headers["x-memento-install"]||"", timestamp=req.headers["x-memento-timestamp"]||"", nonce=req.headers["x-memento-nonce"]||"", signature=req.headers["x-memento-signature"]||"";
  const record=installs.get(install), secret=record?.secret; if(!secret) return false;
  const now=Date.now(), stamp=Number(timestamp); if(!Number.isFinite(stamp)||Math.abs(now-stamp)>120_000||!nonce) return false;
  const nonceKey=install+":"+nonce; if(nonces.has(nonceKey)) return false; nonces.set(nonceKey,now); for(const [k,v] of nonces) if(now-v>180_000) nonces.delete(k);
  return equal(signature,hmac(secret,`${timestamp}\n${nonce}\n${crypto.createHash("sha256").update(body).digest("hex")}`));
}

setInterval(() => {
  const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000;
  for (const [key, value] of installs) if (value?.createdAt < cutoff) installs.delete(key);
  for (const [key, value] of buckets) if (value?.at < Date.now() - 10 * 60 * 1000) buckets.delete(key);
}, 60 * 60 * 1000).unref();

const server=http.createServer(async(req,res)=>{
  try {
    if(req.method==="GET"&&req.url==="/health") return json(res,200,{ok:true,service:"memento-ai",model});
    const body=await read(req);
    if(req.method==="POST"&&req.url==="/api/mobile/v1/register") {
      const data=JSON.parse(body||"{}"); const install=String(data.install_id||""); const proof=String(data.proof||"");
      if(!bootstrap||!install||install.length>128||!equal(proof,hmac(bootstrap,install))) return json(res,403,{error:"invalid_app_proof"});
      if(limited((req.socket.remoteAddress||"")+":register",8)) return json(res,429,{error:"rate_limited"});
      const secret=crypto.randomBytes(32).toString("hex"); installs.set(install,{secret,createdAt:Date.now()}); return json(res,200,{install_secret:secret,expires_in:2592000});
    }
    if(req.method!=="POST"||req.url!=="/api/mobile/v1/ai") return json(res,404,{error:"not_found"});
    if(!auth(req,body)) return json(res,401,{error:"invalid_app_signature"});
    const install=String(req.headers["x-memento-install"]||""); if(limited(install,24)) return json(res,429,{error:"rate_limited"});
    const data=JSON.parse(body||"{}"); const task=String(data.task||""); const allowed=new Set(["summary","metadata","meeting_chat","knowledge_chat"]); if(!allowed.has(task)) return json(res,400,{error:"unsupported_task"});
    const messages=Array.isArray(data.messages)?data.messages.slice(-12):[];
    if(!messages.length) return json(res,400,{error:"messages_required"});
    const controller=new AbortController(); const timer=setTimeout(()=>controller.abort(),115000);
    const response=await fetch(`${upstream}/chat/completions`,{method:"POST",headers:{"authorization":`Bearer ${apiKey}`,"content-type":"application/json","accept":"application/json"},body:JSON.stringify({model,messages,temperature:task==="metadata"?0.1:0.2,max_tokens:task==="metadata"?500:3500}),signal:controller.signal}); clearTimeout(timer);
    const text=await response.text(); if(!response.ok) return json(res,502,{error:"upstream_error",status:response.status});
    const out=JSON.parse(text); const content=out?.choices?.[0]?.message?.content||""; if(!content) return json(res,502,{error:"empty_upstream_response"});
    return json(res,200,{content,model,task});
  } catch(e) { return json(res,e?.message==="too_large"?413:500,{error:"gateway_error"}); }
});
server.listen(port,"127.0.0.1",()=>console.log(`memento-ai listening on ${port}`));
