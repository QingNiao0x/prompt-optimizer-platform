(()=>{
  'use strict';
  const data=JSON.parse(document.getElementById('deck-data').textContent);
  const slides=[...document.querySelectorAll('.slide')];
  const shell=document.getElementById('stage-shell');
  const notes=document.getElementById('notes-panel');
  const overview=document.getElementById('overview');
  const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let index=0,toastTimer,touchX=null,touchY=null;
  function fit(){
    const small=innerWidth<600;
    const maxW=Math.min(innerWidth-(small?20:44),1580);
    const maxH=Math.max(120,innerHeight-(small?193:139));
    const scale=Math.min(maxW/1280,maxH/720);
    document.documentElement.style.setProperty('--scale',scale);
    shell.style.width=(1280*scale)+'px';shell.style.height=(720*scale)+'px';
    document.querySelector('.progress').style.maxWidth=shell.style.width;
    document.getElementById('controls').style.maxWidth=Math.max(360,1280*scale)+'px';
  }
  function noteBlock(title,body){return '<section class="note-block"><h3>'+title+'</h3>'+body+'</section>';}
  function updateNotes(){const d=data[index];document.getElementById('notes-title').textContent=d.title.replace(/\n/g,'，');
    document.getElementById('notes-content').innerHTML='<p class="note-meta">'+String(index+1).padStart(2,'0')+' / 17 · 建议 '+d.seconds+' 秒 · 来源核对 2026-10-05</p>'
      +noteBlock('核心文案','<ul>'+d.copy.map(t=>'<li>'+esc(t)+'</li>').join('')+'</ul>')
      +noteBlock('演讲备注','<p>'+esc(d.speech)+'</p>')
      +noteBlock('演示动作建议','<p>'+esc(d.demo)+'</p>')
      +noteBlock('事实依据','<ul>'+d.sourceDetails.map(s=>'<li><code>'+esc(s.path)+'</code><span class="source-detail">'+esc(s.anchor)+'<br>'+esc(s.reason)+'</span></li>').join('')+'</ul>')
      +noteBlock('视觉与版式','<p>'+esc(d.visual)+'</p>');
    notes.scrollTop=0;
  }
  function go(n,changeHash=true){index=Math.max(0,Math.min(slides.length-1,Number(n)||0));slides.forEach((s,i)=>{s.classList.toggle('active',i===index);s.inert=i!==index;s.setAttribute('aria-hidden',String(i!==index));});
    document.getElementById('counter').textContent=String(index+1).padStart(2,'0')+' / '+slides.length;
    document.getElementById('prev').disabled=index===0;document.getElementById('next').disabled=index===slides.length-1;
    document.getElementById('progress-bar').style.width=((index+1)/slides.length*100)+'%';
    document.getElementById('current-chapter').textContent=data[index].chapter?'CHAPTER '+String(data[index].chapter).padStart(2,'0'):data[index].tag;
    document.title=String(index+1).padStart(2,'0')+' · '+data[index].title.replace(/\n/g,'，')+'｜Prompt Optimizer Platform';
    if(changeHash)history.replaceState(null,'','#slide-'+(index+1));
    updateNotes();
  }
  function toggleNotes(show=!notes.hidden){notes.hidden=!show;document.getElementById('notes-button').setAttribute('aria-expanded',String(show));if(show)document.getElementById('close-notes').focus();else document.getElementById('notes-button').focus();}
  function toast(message){const t=document.getElementById('toast');t.textContent=message;t.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>t.hidden=true,3200);}
  function fullscreen(){if(document.fullscreenElement){document.exitFullscreen().catch(()=>toast('可使用浏览器的退出全屏按钮。'));}else if(document.documentElement.requestFullscreen){document.documentElement.requestFullscreen().catch(()=>toast('此环境限制全屏，可使用浏览器 F11。'));}else toast('此浏览器可使用系统全屏或横屏放映。');}
  document.getElementById('prev').onclick=()=>go(index-1);document.getElementById('next').onclick=()=>go(index+1);
  document.getElementById('notes-button').onclick=()=>toggleNotes(notes.hidden);document.getElementById('close-notes').onclick=()=>toggleNotes(false);
  document.getElementById('mobile-read').onclick=()=>toggleNotes(true);
  document.getElementById('overview-button').onclick=()=>overview.showModal();document.getElementById('close-overview').onclick=()=>overview.close();
  document.getElementById('fullscreen').onclick=fullscreen;
  document.addEventListener('click',e=>{const b=e.target.closest('[data-go]');if(b){go(b.dataset.go);if(overview.open)overview.close();}});
  overview.addEventListener('click',e=>{if(e.target===overview){const r=overview.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)overview.close();}});
  document.addEventListener('keydown',e=>{
    if(e.key==='Escape'){if(overview.open)return;if(!notes.hidden){toggleNotes(false);e.preventDefault();}return;}
    if(overview.open||/INPUT|TEXTAREA|SELECT/.test(e.target.tagName)||e.altKey||e.ctrlKey||e.metaKey)return;
    if(e.key==='n'||e.key==='N'){toggleNotes(notes.hidden);e.preventDefault();return;}
    if(!notes.hidden)return;
    if((e.key===' '||e.key==='Enter')&&e.target.closest('button,a'))return;
    if(['ArrowRight','PageDown',' '].includes(e.key)){go(index+1);e.preventDefault();}
    else if(['ArrowLeft','PageUp'].includes(e.key)){go(index-1);e.preventDefault();}
    else if(e.key==='Home'){go(0);e.preventDefault();}
    else if(e.key==='End'){go(slides.length-1);e.preventDefault();}
    else if(/^[oO]$/.test(e.key)){overview.showModal();e.preventDefault();}
    else if(/^[fF]$/.test(e.key)){fullscreen();e.preventDefault();}
  });
  shell.addEventListener('touchstart',e=>{if(e.touches.length===1){touchX=e.touches[0].clientX;touchY=e.touches[0].clientY;}},{passive:true});
  shell.addEventListener('touchend',e=>{if(touchX===null||!notes.hidden)return;const dx=e.changedTouches[0].clientX-touchX,dy=e.changedTouches[0].clientY-touchY;if(Math.abs(dx)>48&&Math.abs(dx)>Math.abs(dy)*1.4)go(index+(dx<0?1:-1));touchX=touchY=null;},{passive:true});
  window.addEventListener('resize',fit);document.addEventListener('fullscreenchange',()=>{document.getElementById('fullscreen').textContent=document.fullscreenElement?'退出全屏':'全屏';fit();});
  window.addEventListener('hashchange',()=>{const n=Number(location.hash.match(/^#slide-(\d+)$/)?.[1]);if(n)go(n-1,false);});
  fit();go(Number(location.hash.match(/^#slide-(\d+)$/)?.[1]||1)-1,false);
})();
