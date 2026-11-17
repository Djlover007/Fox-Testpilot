// Anmeldung bei myMediaMarkt / mySaturn: E-Mail und Passwort eintragen und EINMAL absenden.
// Läuft nur auf der Domain des jeweiligen Shops (Prüfung unten).
(function(){var A=__ACCOUNTS__;var h=location.hostname,acc=null;
for(var d in A){if(h===d||h.slice(-(d.length+1))==='.'+d){acc=A[d];}}
if(!acc)return;var E=acc.e,P=acc.p;
if(window.top!==window||window.__foxLogin)return;window.__foxLogin=true;
function vis(e){if(e.disabled||e.readOnly)return false;var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
function setVal(e,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(e,v);
  ['input','change','blur'].forEach(function(n){e.dispatchEvent(new Event(n,{bubbles:true}));});}
function mailField(root){var l=[].slice.call(root.querySelectorAll('input')).filter(function(e){
  var k=(e.type+' '+e.name+' '+e.id+' '+(e.getAttribute('autocomplete')||'')).toLowerCase();
  return vis(e)&&/email|e-mail|user|login/.test(k)&&e.type!=='password'&&!/search|newsletter/.test(k);});return l[0]||null;}
var sent=false,runs=0;
var t=setInterval(function(){try{
  if(++runs>45||sent)return clearInterval(t);
  var pw=[].slice.call(document.querySelectorAll('input[type=password]')).filter(vis)[0];
  var onLogin=/login|auth|signin|anmeld/i.test(location.href);
  if(!pw){if(onLogin){var m0=mailField(document);if(m0&&!m0.value)setVal(m0,E);}return;}
  if(/new-password/.test(pw.getAttribute('autocomplete')||'')||document.querySelectorAll('input[type=password]').length>1)return clearInterval(t);
  var root=pw.form||document,m=mailField(root);
  if(m&&!m.value)setVal(m,E);
  if(!pw.value)setVal(pw,P);
  sent=true;
  var tried=0;try{tried=+sessionStorage.getItem('foxLoginTried')||0;sessionStorage.setItem('foxLoginTried',tried+1);}catch(x){}
  if(tried>0)return;
  setTimeout(function(){var b=root.querySelector('button[type=submit],input[type=submit]');if(b&&!b.disabled)b.click();},800);
}catch(x){}},1000);
})();
