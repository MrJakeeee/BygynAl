let csrf;
export async function api(path, method='GET', body) {
  if(method!=='GET'&&!csrf) csrf=await api('/auth/csrf');
  const response=await fetch('/api'+path,{method,credentials:'same-origin',headers:{...(body?{'Content-Type':'application/json'}:{}),...(method!=='GET'?{[csrf.headerName]:csrf.token}:{})},body:body?JSON.stringify(body):undefined});
  const data=response.status===204?{}:await response.json();
  if(!response.ok){if(response.status===401)csrf=null;throw Error(data.message||'Не удалось выполнить запрос');}
  return data;
}
let toastTimer;
export function toast(message){const box=document.querySelector('#toast');if(!box)return;box.textContent=message;box.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>box.hidden=true,6000);}
async function submit(form,action){const button=form.querySelector('[type=submit]');const error=form.querySelector('.form-error');if(error)error.hidden=true;button.disabled=true;try{await action();}catch(e){if(error){error.textContent=e.message;error.hidden=false;}else toast(e.message);}finally{button.disabled=false;}}
const payload=form=>Object.fromEntries(new FormData(form));
const safe=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
document.querySelector('[data-profile-form]')?.addEventListener('submit',e=>{e.preventDefault();submit(e.target,async()=>{await api('/profile','PATCH',payload(e.target));const current=await api('/profile');for(const key of ['name','phone']){e.target.elements[key].value=current[key];e.target.elements[key].defaultValue=current[key];}toast('Изменения сохранены');document.querySelectorAll('.identity-heading h3,.header-profile b').forEach(x=>x.textContent=current.name);document.querySelector('.identity-heading small').textContent=current.phone;});});
document.querySelector('[data-password-form]')?.addEventListener('submit',e=>{
  e.preventDefault();submit(e.target,async()=>{const data=payload(e.target);if(data.password!==document.querySelector('#confirmPassword').value)throw Error('Пароли не совпадают. Повторите новый пароль.');await api('/profile/password','POST',data);location.href='/login?password=changed';});
});
document.querySelector('#newPassword')?.addEventListener('input',e=>{const value=e.target.value;const score=(value.length>=8?1:0)+(/[a-zа-я]/i.test(value)&&/\d/.test(value)?1:0)+(/[A-ZА-Я]/.test(value)?1:0)+(/[^\p{L}\p{N}]/u.test(value)&&value.length>=12?1:0);document.querySelectorAll('.password-meter span').forEach((s,i)=>s.classList.toggle('filled',i<score));});
document.querySelectorAll('[data-toggle-password]').forEach(b=>b.addEventListener('click',()=>{const input=b.previousElementSibling;const show=input.type==='password';input.type=show?'text':'password';b.setAttribute('aria-pressed',String(show));b.setAttribute('aria-label',show?'Скрыть пароль':'Показать пароль');}));
document.querySelectorAll('[data-logout]').forEach(b=>b.addEventListener('click',async()=>{b.disabled=true;try{await api('/logout','POST');location.href='/';}catch(e){toast(e.message);b.disabled=false;}}));
document.querySelectorAll('[data-close-dialog]').forEach(b=>b.addEventListener('click',()=>b.closest('dialog').close()));
document.querySelectorAll('dialog').forEach(d=>d.addEventListener('click',e=>{if(e.target===d){const r=d.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)d.close();}}));
document.querySelectorAll('.category-tabs input').forEach(input=>input.addEventListener('change',()=>input.form.requestSubmit()));
document.querySelector('[data-clear-search]')?.addEventListener('click',e=>{const form=e.target.closest('form');form.elements.q.value='';form.requestSubmit();});
document.querySelectorAll('[data-offer-details]').forEach(b=>b.addEventListener('click',()=>{document.querySelector('#offer-title').textContent=b.dataset.title;document.querySelector('#offer-composition').textContent=b.dataset.composition;document.querySelector('#offer-dialog').showModal();}));

// Sensitive profile changes use the existing challenge API and never persist credentials.
let challenge=null,accountAction=null,enabled=false;
const accountDialog=document.querySelector('#account-dialog');
document.querySelectorAll('[data-account-action]').forEach(b=>b.addEventListener('click',()=>{
  challenge=null;accountAction=b.dataset.accountAction;enabled=b.dataset.enabled==='true';
  document.querySelector('#account-dialog-title').textContent=accountAction==='email'?'Изменить электронную почту':enabled?'Включить двухфакторную защиту':'Отключить двухфакторную защиту';
  document.querySelector('#account-dialog-fields').innerHTML=(accountAction==='email'?'<label for="change-email">Новый Gmail</label><input id="change-email" name="email" type="email" required autocomplete="email" maxlength="254">':'')+'<label for="change-password">Текущий пароль</label><input id="change-password" name="password" type="password" required autocomplete="current-password" maxlength="72">';
  const form=document.querySelector('#account-dialog-form');form.querySelector('.form-error').hidden=true;form.querySelector('[type=submit]').textContent='Получить код';accountDialog.showModal();
}));
document.querySelector('#account-dialog-form')?.addEventListener('submit',e=>{e.preventDefault();submit(e.target,async()=>{
  if(!challenge){const data=payload(e.target);if(accountAction==='factor')data.enabled=enabled;const result=await api(accountAction==='factor'?'/profile/two-factor':'/profile/email','POST',data);challenge=result.challengeId;
    document.querySelector('#account-dialog-fields').innerHTML='<p>Введите шестизначный код из письма. После изменения потребуется войти заново.</p><label for="change-code">Код подтверждения</label><input id="change-code" name="code" required inputmode="numeric" pattern="[0-9]{6}" minlength="6" maxlength="6" autocomplete="one-time-code">';
    e.target.querySelector('[type=submit]').textContent='Подтвердить';document.querySelector('#change-code').focus();
  }else{await api(accountAction==='factor'?'/profile/two-factor/verify?enabled='+enabled:'/profile/email/verify','POST',{challengeId:challenge,code:payload(e.target).code});location.href='/login';}
});});
accountDialog?.addEventListener('close',()=>{challenge=null;document.querySelector('#account-dialog-fields').replaceChildren();});

const createDialog=document.querySelector('#create-dialog');
let createAction='';
const input=(name,label,type='text',extra='')=>'<label for="create-'+name+'">'+label+'</label><input id="create-'+name+'" name="'+name+'" type="'+type+'" required '+extra+'>';
document.querySelectorAll('[data-admin-create]').forEach(b=>b.addEventListener('click',async()=>{
  createAction=b.dataset.adminCreate;let html='';
  try{
    if(createAction==='establishment'){document.querySelector('#create-title').textContent='Новое заведение';html=input('name','Название','text','maxlength="160"')+input('address','Адрес','text','maxlength="300"');}
    else{const places=await api('/admin/establishments');if(!places.length){toast('Сначала создайте заведение');document.querySelector('#establishments').scrollIntoView();return;}
      document.querySelector('#create-title').textContent='Новый сотрудник';html=input('name','Имя','text','maxlength="100"')+input('email','Gmail','email','maxlength="254"')+input('phone','Телефон','tel','maxlength="25" pattern="\\+?[0-9 ()\\-]{7,25}"')+'<label for="create-establishment">Заведение</label><select id="create-establishment" name="establishmentId" required>'+places.map(p=>'<option value="'+safe(p.id)+'">'+safe(p.name)+'</option>').join('')+'</select><p class="dialog-description">Временный пароль отправим на e-mail сотрудника. При первом входе его нужно сменить.</p>';
    }
    document.querySelector('#create-fields').innerHTML=html;document.querySelector('#create-form .form-error').hidden=true;createDialog.showModal();
  }catch(e){toast(e.message);}
}));
document.querySelector('#create-form')?.addEventListener('submit',e=>{e.preventDefault();submit(e.target,async()=>{await api(createAction==='seller'?'/admin/sellers':'/admin/establishments','POST',payload(e.target));location.reload();});});
let blockTarget=null;
document.querySelectorAll('[data-block-id]').forEach(b=>b.addEventListener('click',()=>{blockTarget={id:b.dataset.blockId,blocked:b.dataset.blockState==='true'};document.querySelector('#block-title').textContent=blockTarget.blocked?'Заблокировать пользователя?':'Разблокировать пользователя?';document.querySelector('#block-description').textContent=b.dataset.memberName+(blockTarget.blocked?' потеряет доступ к сервису. Активные сессии будут завершены.':' снова сможет войти в сервис.');document.querySelector('#block-form .form-error').hidden=true;document.querySelector('#block-dialog').showModal();}));
document.querySelector('#block-form')?.addEventListener('submit',e=>{e.preventDefault();submit(e.target,async()=>{await api('/admin/users/'+encodeURIComponent(blockTarget.id)+'/block','PATCH',{blocked:blockTarget.blocked});location.reload();});});
