const content=document.querySelector('#content');

let user=null, csrf=null, pending=null, activeTab='profile';
const escape=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const roles={USER:'Покупатель',SELLER:'Сотрудник заведения',ADMIN:'Администратор'};
async function api(path,method='GET',body){
  if(method!=='GET'&&!csrf) csrf=await api('/auth/csrf');
  const response=await fetch('/api'+path,{method,credentials:'include',headers:{...(body?{'Content-Type':'application/json'}:{}),...(method!=='GET'?{[csrf.headerName]:csrf.token}:{})},body:body?JSON.stringify(body):undefined});
  const data=response.status===204?{}:await response.json();
  if(!response.ok){if(response.status===401&&user){user=null;csrf=null;render('login');}throw Error(data.message||'Не удалось выполнить запрос');}
  return data;
}
function toast(text){const el=document.querySelector('#toast');el.textContent=text;el.hidden=false;setTimeout(()=>el.hidden=true,5000);}
function field(name,label,type='text',attrs=''){return `<label for="${name}">${label}</label><input id="${name}" name="${name}" type="${type}" required ${attrs}>`;}
const emailField=()=>field('email','Gmail','email','autocomplete="email" maxlength="254" placeholder="you@gmail.com"');
const passwordField=(name='password',label='Пароль',fresh=false)=>field(name,label,'password',`autocomplete="${fresh?'new-password':'current-password'}" ${fresh?'minlength="8"':''} maxlength="72"`);
const codeField=()=>field('code','Код из письма','text','inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" minlength="6" maxlength="6" placeholder="000000"');
function form(inner,submit){return `<form>${inner}<div class="error" id="error" role="alert" hidden></div><button class="primary" type="submit">${submit} ↗</button></form>`;}
function header() {}
function render(page='login'){
  header();content.classList.toggle('wide',!!user);let html='';
  if(page==='register')html=`<span class="step">НАЧНИТЕ СЕГОДНЯ</span><h2>Создать аккаунт</h2><p class="subtitle">Зарегистрируйтесь как покупатель.<br>Подтвердите Gmail, чтобы завершить регистрацию.</p>${form(field('name','Имя','text','autocomplete="given-name" maxlength="100"')+emailField()+field('phone','Телефон','tel','autocomplete="tel" pattern="\\+?[0-9 \\x28\\x29\\x2d]{7,25}" placeholder="+996 700 123 456"')+passwordField('password','Пароль',true)+'<p class="hint">Не менее 8 символов: буквы и цифры. Учётные записи сотрудников создаёт администратор.</p>','Получить код')}<div class="form-footer">Уже с нами? <button class="text-button" data-page="login">Войти</button></div>`;
  else if(page==='forgot')html=`<span class="step">ДОСТУП К АККАУНТУ</span><h2>Забыли пароль?</h2><p class="subtitle">Отправим код подтверждения на ваш Gmail. Код действует 30 минут.</p>${form(emailField(),'Отправить код')}<button class="text-button" data-page="login">← Вернуться ко входу</button>`;
  else if(page==='verify')html=`<span class="step">ПОДТВЕРЖДЕНИЕ ПОЧТЫ</span><h2>${pending.type==='reset'?'Новый пароль':'Проверьте Gmail'}</h2><p class="subtitle">${escape(pending.message||'Введите шестизначный код из письма. Проверьте также папку «Спам».')}</p>${form(codeField()+(pending.type==='reset'?passwordField('password','Новый пароль',true):''),'Подтвердить')}<button class="text-button" data-action="resend">Отправить код повторно</button><button class="text-button" data-action="cancel">← Назад</button><p class="hint">Повторная отправка доступна через 60 секунд. Предыдущий код перестанет действовать.</p>`;
  else if(page==='profile'&&user){location.href=user.mustChangePassword?'/profile':user.role==='SELLER'?'/seller':user.role==='ADMIN'?'/admin':'/profile';return;}
  else html=`<span class="step">РАДЫ ВАС ВИДЕТЬ</span><h2>С возвращением</h2><p class="subtitle">Войдите, чтобы забрать что-то вкусное сегодня.</p>${form(emailField()+passwordField()+'<button type="button" class="text-button forgot" data-page="forgot">Забыли пароль?</button>','Войти')}<div class="form-footer">Ещё нет аккаунта? <button class="text-button" data-page="register">Зарегистрироваться</button></div><div class="panel"><p class="hint">Единый вход для покупателей, сотрудников заведений и администратора.</p></div>`;
  content.innerHTML=html;
  content.querySelector('form')?.addEventListener('submit',event=>submitForm(event,page));
}
async function busy(form,fn){const button=form.querySelector('[type=submit]');const err=form.querySelector('#error');button.disabled=true;err.hidden=true;try{await fn();}catch(e){err.textContent=e.message;err.hidden=false;}finally{button.disabled=false;}}
async function submitForm(event,page){event.preventDefault();const formEl=event.target;const data=Object.fromEntries(new FormData(formEl));await busy(formEl,async()=>{
  if(page==='register'){const r=await api('/auth/register','POST',data);pending={...r,type:'register',request:data};render('verify');}
  else if(page==='forgot'){const r=await api('/auth/password/forgot','POST',data);pending={...r,type:'reset',request:data};render('verify');}
  else if(page==='verify')await verify(data);
  else {const r=await api('/auth/login','POST',data);if(r.twoFactorRequired){pending={...r.challenge,type:'login',request:data};render('verify');}else{user=r;csrf=null;activeTab='profile';render('profile');}}
});}
async function verify(data){
  const body={challengeId:pending.challengeId,...data};const type=pending.type;
  const paths={register:'/auth/register/verify',reset:'/auth/password/reset',login:'/auth/login/verify',factor:`/profile/two-factor/verify?enabled=${pending.enabled}`,email:'/profile/email/verify'};
  const r=await api(paths[type],'POST',body);pending=null;
  if(type==='login'){user=r;activeTab='profile';render('profile');}
  else{user=null;csrf=null;render('login');toast(r.message);}
}
document.addEventListener('click',async e=>{
  const b=e.target.closest('button');if(!b)return;
  try{
    if(b.dataset.page){pending=null;render(b.dataset.page);}
    const action=b.dataset.action;
    if(action==='logout'){await api('/logout','POST');user=null;csrf=null;pending=null;render('login');}
    if(action==='back-profile')render('profile');
    
    if(action==='cancel'){const profile=!!user;pending=null;render(profile?'profile':'login');}
    if(action==='resend'){
      b.disabled=true;const paths={register:'/auth/register',reset:'/auth/password/forgot',login:'/auth/login',factor:'/profile/two-factor',email:'/profile/email'};
      try{let r=await api(paths[pending.type],'POST',pending.request);if(pending.type==='login')r=r.challenge;pending={...pending,...r};toast('Новый код отправлен');}finally{b.disabled=false;}
    }
  }catch(err){toast(err.message);}
});
render(content.dataset.authPage||'login');
