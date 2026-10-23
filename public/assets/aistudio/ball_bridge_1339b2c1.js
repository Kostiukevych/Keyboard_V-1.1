/* ball_bridge.js: мост между trackball.html и сферой в iframe (holo_ball.html / earth_ball.html).
   1) Ввод: родитель шлёт нормированные координаты пальца (0..1 по шару); здесь они превращаются
      в настоящие pointer-события, так что сфера живёт своей родной логикой (инерция, молнии, ряби).
   2) Готовность: после 3-го отрисованного кадра шлём родителю {tb:1,k:'ready'} — родитель до этого
      держит iframe невидимым (нет чёрного квадрата). Запасной таймер 2.5 с.
   3) Пауза: родитель шлёт {tb:1,k:'pause'} / {tb:1,k:'resume'}; пока пауза — requestAnimationFrame сферы
      не вызывает кадры (ноль нагрузки на CPU/GPU, когда шар скрыт).
   Вне iframe (открыли файл отдельно) скрипт ничего не делает.
   Страница сферы ОБЯЗАНА крутить анимацию через глобальный requestAnimationFrame (не через сохранённую ссылку). */
(function(){
  if(window.parent===window)return;

  /* ---- прозрачность: тот же color-scheme, что у родителя, иначе Chromium красит фон iframe в чёрный ---- */
  var st=document.createElement('style');
  st.textContent='html,body{background:transparent!important;overflow:hidden;color-scheme:normal!important}.info,#msg,.halo{display:none!important}#g{width:100vmin!important;height:100vmin!important}';
  document.head.appendChild(st);

  /* ---- ready + пауза через обёртку над requestAnimationFrame ---- */
  var realRAF=window.requestAnimationFrame.bind(window),paused=false,held=[],frames=0,sent=false;
  function post(k){try{window.parent.postMessage({tb:1,k:k},'*')}catch(e){}}
  function sendReady(){if(sent)return;sent=true;post('ready')}
  window.requestAnimationFrame=function(cb){
    if(paused){held.push(cb);return 0}
    return realRAF(function(t){
      cb(t);
      if(!sent&&++frames>=3)realRAF(sendReady);   // кадр уже показан на экране
    });
  };
  setTimeout(sendReady,2500);

  /* ---- ввод ---- */
  var isEarth=!!document.getElementById('ov')||!!document.querySelector('meta[name="ball"][content="earth"]');
  var tgt=isEarth?document:document.getElementById('c');
  if(!tgt)return;
  if(!isEarth)tgt.setPointerCapture=function(){};
  var GAIN=isEarth?1:1.5,ax=0,ay=0;
  function fire(type,x,y){
    var W=innerWidth,H=innerHeight,cx,cy;
    if(isEarth){cx=x*W;cy=y*H}else{cx=W/2+(x-ax)*W*GAIN;cy=H/2+(y-ay)*H*GAIN}
    try{tgt.dispatchEvent(new PointerEvent(type,{pointerId:91,pointerType:'touch',isPrimary:true,button:0,buttons:type==='pointerup'?0:1,clientX:cx,clientY:cy,bubbles:true,cancelable:true}))}catch(e){}
  }
  window.addEventListener('message',function(e){
    var d=e.data;if(!d||d.tb!==1||e.source!==window.parent)return;
    if(d.k==='down'){ax=d.x;ay=d.y;fire('pointerdown',d.x,d.y)}
    else if(d.k==='move')fire('pointermove',d.x,d.y);
    else if(d.k==='up')fire('pointerup',d.x,d.y);
    else if(d.k==='pause')paused=true;
    else if(d.k==='resume'&&paused){paused=false;var h=held;held=[];for(var i=0;i<h.length;i++)window.requestAnimationFrame(h[i])}
  });
})();
