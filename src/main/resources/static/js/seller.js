// Кабинет сотрудника заведения: сведения о заведении и предложения (ТЗ 4.3, 4.4).
// Формы с фото отправляем как multipart/form-data, CSRF-токен берём так же, как в site.js.
import {api, toast} from '/js/site.js';

let csrf;
async function send(path, formData) {
  if (!csrf) csrf = await api('/auth/csrf');
  const response = await fetch('/api' + path, {method: 'POST', credentials: 'same-origin', headers: {[csrf.headerName]: csrf.token}, body: formData});
  const data = await response.json().catch(() => ({}));
  if (!response.ok) { if (response.status === 401) csrf = null; throw Error(data.message || 'Не удалось сохранить'); }
  return data;
}

async function submit(form, action) {
  const button = form.querySelector('[type=submit]');
  const error = form.querySelector('.form-error');
  error.hidden = true; button.disabled = true;
  try { await action(); }
  catch (e) { error.textContent = e.message; error.hidden = false; error.scrollIntoView({block: 'center'}); }
  finally { button.disabled = false; }
}

// Проверки в браузере только для удобства. Окончательно всё проверяет сервер.
function checkPhotos(input) {
  const files = [...(input?.files || [])];
  if (files.length > 3) throw Error('Можно загрузить не больше 3 фото');
  if (files.some(f => f.size > 5 * 1024 * 1024)) throw Error('Фото должно быть не больше 5 МБ');
}

document.querySelector('[data-establishment-form]')?.addEventListener('submit', e => {
  e.preventDefault();
  submit(e.target, async () => {
    checkPhotos(e.target.elements.logo);
    const data = new FormData(e.target);
    const start = data.get('openingStart'), end = data.get('openingEnd');
    if (!start || !end) throw Error('Укажите время открытия и закрытия');
    if (start >= end) throw Error('Время закрытия должно быть позже времени открытия');
    data.set('openingHours', start + '–' + end);
    await send('/seller/establishment', data);
    location.href = '/seller';
  });
});

const offerForm = document.querySelector('[data-offer-form]');
offerForm?.addEventListener('submit', e => {
  e.preventDefault();
  submit(offerForm, async () => {
    checkPhotos(offerForm.elements.photos);
    const id = offerForm.dataset.offerId;
    await send(id ? '/seller/offers/' + encodeURIComponent(id) : '/seller/offers', new FormData(offerForm));
    location.href = '/seller';
  });
});

// Процент скидки показываем сразу, пока сотрудник вводит цены.
function updateDiscount() {
  const badge = document.querySelector('#discount-badge');
  if (!badge || !offerForm) return;
  const original = parseFloat(offerForm.elements.originalPrice.value.replace(',', '.'));
  const price = parseFloat(offerForm.elements.price.value.replace(',', '.'));
  badge.textContent = original > 0 && price > 0 && price < original
    ? 'Скидка −' + Math.floor((original - price) * 100 / original) + '%'
    : 'Скидка —';
}
offerForm?.elements.originalPrice.addEventListener('input', updateDiscount);
offerForm?.elements.price.addEventListener('input', updateDiscount);
updateDiscount();

// Предпросмотр выбранного логотипа.
document.querySelectorAll('[data-preview]').forEach(input => input.addEventListener('change', () => {
  const file = input.files[0];
  if (file) document.querySelector(input.dataset.preview).src = URL.createObjectURL(file);
}));

// Снятие предложения с продажи — с подтверждением.
let withdrawId = null;
const withdrawDialog = document.querySelector('#withdraw-dialog');
document.querySelectorAll('[data-withdraw]').forEach(b => b.addEventListener('click', () => {
  withdrawId = b.dataset.withdraw;
  document.querySelector('#withdraw-description').textContent = '«' + b.dataset.title + '» исчезнет с витрины. Уже оплаченные заказы остаются в силе и выдаются как обычно.';
  withdrawDialog.querySelector('.form-error').hidden = true;
  withdrawDialog.showModal();
}));
document.querySelector('#withdraw-form')?.addEventListener('submit', e => {
  e.preventDefault();
  submit(e.target, async () => {
    const result = await api('/seller/offers/' + encodeURIComponent(withdrawId) + '/withdraw', 'POST');
    toast(result.message);
    location.reload();
  });
});
