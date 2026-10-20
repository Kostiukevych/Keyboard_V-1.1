/* ball_bridge.js: мост между trackball.html и сферой в iframe (holo_ball.html / earth_ball.html).
   Родитель шлёт нормированные координаты пальца (0..1 по шару); здесь они превращаются
   в настоящие pointer-события, так что сфера живёт своей родной логикой (инерция, молнии, ряби).
   Вне iframe (открыли файл отдельно) скрипт ничего не делает. */
(function(){
  if(window.parent===window)return;
  var st=document.createElement('style');
  st.textContent='html,body{background:transparent!important;overflow:hidden}.info,#msg,.halo{display:none!important}#g{width:100vmin!important;height:100vmin!important}';
  document.head.appendChild(st);
  var isEarth=!!document.getElementById('ov');
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
  });
})();
