import type {FeaturePlugin,UiEffect,UiResult,UiNode,Json} from '../../../sdk';

/**
 * 题库练习（不联网、不需要学校账号）。
 *
 * 移植自安卓项目 app/src/main/java/com/hnnujw/course/review/：
 *   - 章节**自然序**：照 ChapterOrder 的思路按「第一章、第二章」排，而不是按文字编码；
 *   - 乱序：照 QuestionOrder 的思路用题号哈希排序 —— 这里必须**确定性**，
 *     插件的 reducer 要保持可重放，用 Math.random() 会让同一份 state 每次重组都变；
 *   - 判分按钮一律叫「**确认答案**」，它的语义只是"现在揭晓解析"，不是"这题算不算数"；
 *   - **交卷补判**（judgePending）：只选了答案、没点确认就往下走的题，提交时一并判分；
 *     从头到尾没碰过的才算未作答，且不算答错；
 *   - 正确率的分母**只有已判分的题**（对 + 错），与 PracticeSummary 一致；
 *   - 七档鼓励语照 encouragementFor 的分档原文搬过来。
 *
 * 平台约束导致的差异，都写在 README 里，这里只记结论：
 *   1. 没有 SQLite，题目放 state、错题/收藏放 storage（每作用域 256 KiB），
 *      所以导入题库上限 300 题、单题题干 500 字；
 *   2. 没有可靠的前台计时能力，安卓端「考试限时倒计时 + 到点自动交卷」未移植，
 *      考试模式保留它的另一半语义：**交卷前不判分、不显示对错与解析**；
 *   3. 没有悬浮窗能力，安卓端那颗 AI 悬浮球在插件里改成了页面上的按钮
 *      （AI 答疑单独做成了另一个插件）。
 */
const BUILTIN_NAME='内置演示题库';
const MAX_QUESTIONS=300;
const MAX_STEM=500;
const MAX_FILE=262144;

type Q={id:string;type:'single'|'multi';stem:string;options:string[];answer:string;chapter:string;explanation:string};
type Records=Record<string,{wrong:number;right:number}>;
type Screen='setup'|'practice'|'sheet'|'summary';
type Mode='order'|'shuffle'|'exam'|'memorize';
type State={
 serial:number;pending:string;status:string;page:'main'|'library'|'wrong';loaded:boolean;
 screen:Screen;bankName:string;bankIsBuiltin:boolean;all:Q[];order:number[];
 chapters:string[];picked:string[];mode:Mode;autoNext:boolean;index:number;
 answers:Record<string,string>;judged:Record<string,boolean>;rights:Record<string,boolean>;
 favorites:string[];records:Records;summary:string;importText:string;wrongText:string;
};

// ── 内置演示题库：通用通识题，不含任何学校的真实试题 ──────────────────
const RAW:Array<[string,string,string[],string,string]>=[
 ['第一章 计算机基础','在计算机中，1 字节（Byte）等于多少位（bit）？',['4 位','8 位','16 位','32 位'],'B','1 Byte = 8 bit，这是存储容量的基本换算关系。'],
 ['第一章 计算机基础','下列存储器中，断电后数据会丢失的是（ ）。',['ROM','RAM','硬盘','U 盘'],'B','RAM 是易失性存储器，断电后内容立即丢失。'],
 ['第一章 计算机基础','十进制数 13 转换为二进制是（ ）。',['1101','1011','1110','1001'],'A','13 = 8 + 4 + 1，对应二进制 1101。'],
 ['第一章 计算机基础','1 KB 等于多少字节？',['1000','1024','1048','512'],'B','1 KB = 1024 Byte（2 的 10 次方）。'],
 ['第二章 操作系统','下列属于操作系统的是（ ）。',['Windows','Linux','macOS','Word'],'ABC','Word 是应用软件，不是操作系统。'],
 ['第二章 操作系统','关于线程，下列说法正确的是（ ）。',['线程是进程中的一个执行单元','线程拥有完全独立的地址空间','线程之间不能并发执行','线程等同于一个程序'],'A','同一进程的线程共享地址空间，因此切换成本低于进程。'],
 ['第二章 操作系统','文件系统中的「绝对路径」指的是（ ）。',['从根目录开始的路径','从当前目录开始的路径','只包含文件名的路径','网络地址'],'A','绝对路径从根目录出发，与当前所在目录无关。'],
 ['第二章 操作系统','下列不属于死锁产生的必要条件的是（ ）。',['互斥条件','占有且等待','不可抢占','内存充足'],'D','内存是否充足与死锁的四个必要条件无关。'],
 ['第三章 网络基础','IP 地址 127.0.0.1 通常指（ ）。',['本机','默认网关','DNS 服务器','广播地址'],'A','127.0.0.1 是回环地址，指向本机。'],
 ['第三章 网络基础','HTTP 协议的默认端口是（ ）。',['21','23','80','443'],'C','80 是 HTTP 默认端口，443 是 HTTPS。'],
 ['第三章 网络基础','下列协议中属于传输层的是（ ）。',['TCP','UDP','IP','HTTP'],'AB','TCP 与 UDP 在传输层，IP 在网络层，HTTP 在应用层。'],
 ['第三章 网络基础','子网掩码的作用是（ ）。',['区分网络号与主机号','加密传输数据','提高带宽','分配域名'],'A','子网掩码用来划分 IP 地址中的网络部分与主机部分。']
];
const BUILTIN:Q[]=RAW.map(([chapter,stem,options,answer,explanation],i)=>({
 id:'b'+(i+1),type:answer.length>1?'multi':'single',stem,options,answer,chapter,explanation}));

// ── 纯函数：排序、判分、统计 ──────────────────────────────────────────
const CN:Record<string,number>={'一':1,'二':2,'三':3,'四':4,'五':5,'六':6,'七':7,'八':8,'九':9,'十':10};
/** 章节自然序：先把「第一章 / 第 2 章」里的数取出来，取不到就排在最后并保持原顺序。 */
function chapterKey(name:string):number{
 const arabic=/[0-9]+/.exec(name);
 if(arabic)return Number(arabic[0]);
 const text=name.replace(/^第/,'').trim();
 if(!text)return Number.MAX_SAFE_INTEGER;
 if(text[0]==='十')return 10+(CN[text[1]]??0);
 const head=CN[text[0]];
 if(head===undefined)return Number.MAX_SAFE_INTEGER;
 if(text[1]==='十')return head*10+(CN[text[2]]??0);
 return head;
}
/**
 * 题号哈希。
 *
 * 末尾必须有一次混淆（Murmur3 finalizer）：只用 `h*31+c` 的话，连续题号
 * b1、b2…b12 的哈希是单调递增的，乱序会**退化成原顺序** —— 实测正是如此，
 * 加混淆之后同样的题号才会真正被打散。
 */
function hash(text:string):number{
 let h=0;
 for(let i=0;i<text.length;i++)h=(Math.imul(h,31)+text.charCodeAt(i))|0;
 h^=h>>>16;h=Math.imul(h,2246822507);h^=h>>>13;h=Math.imul(h,3266489909);h^=h>>>16;
 return h|0;
}
/** 出题顺序：顺序模式按章节自然序，乱序按题号哈希（确定性，可重放）。 */
function buildOrder(all:Q[],mode:Mode):number[]{
 return all.map((_,i)=>i).sort((a,b)=>{
  if(mode==='shuffle'){const ha=hash(all[a].id),hb=hash(all[b].id);return ha!==hb?ha-hb:all[a].id<all[b].id?-1:1;}
  const ka=chapterKey(all[a].chapter),kb=chapterKey(all[b].chapter);
  return ka!==kb?ka-kb:a-b;
 });
}
function normalizeAnswer(text:string,optionCount:number):string{
 const letters='ABCDEFGH'.slice(0,Math.max(0,Math.min(8,optionCount)));
 return text.toUpperCase().split('').filter(c=>letters.includes(c))
  .filter((c,i,arr)=>arr.indexOf(c)===i).sort().join('');
}
function isRight(q:Q,answer:string):boolean{return normalizeAnswer(answer,q.options.length)===q.answer;}
/** 与安卓端 encouragementFor 同档位、同文案。 */
function encouragement(percent:number,judged:number,total:number):string{
 if(total<=0)return '这一轮没有题目。';
 if(judged<=0)return '这一轮还没有作答记录 —— 先做几道题再提交吧。';
 if(percent>=95)return '几乎全对，这个题库你已经吃透了。';
 if(percent>=85)return '非常棒，手感很稳，保持住。';
 if(percent>=70)return '不错，大部分都拿下了 —— 把错的几道再过一遍就稳了。';
 if(percent>=50)return '已经过半了。错题本帮你记着，刷一遍会明显好转。';
 if(percent>0)return '别急着下结论，先把错题的解析读一遍，再来一轮会不一样。';
 return '这一轮还没拿到第一道题，慢慢来 —— 先看解析，再刷一遍会不一样。';
}
function decodeBase64(text:string):string{
 const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/',bytes:number[]=[];
 let bits=0,value=0;
 for(const ch of text.replace(/=+$/,'')){
  const n=alphabet.indexOf(ch);if(n<0)throw new Error('文件不是有效的 Base64');
  value=(value<<6)|n;bits+=6;
  if(bits>=8){bits-=8;bytes.push((value>>bits)&255);}
 }
 return decodeURIComponent(bytes.map(b=>'%'+b.toString(16).padStart(2,'0')).join(''));
}
/** 兼容安卓端题库 JSON：{version,name,questions:[...]}、裸数组、单题对象都认。 */
function parseBank(text:string):{name:string;questions:Q[];error:string}{
 let root:unknown;
 try{root=JSON.parse(text);}catch{return {name:'',questions:[],error:'不是合法的 JSON 文件。'};}
 const list:unknown[]=Array.isArray(root)?root
  :Array.isArray((root as {questions?:unknown[]})?.questions)?(root as {questions:unknown[]}).questions
  :[root];
 const name=typeof (root as {name?:unknown})?.name==='string'?String((root as {name?:unknown}).name):'导入题库';
 const questions:Q[]=[];
 for(const [i,item] of list.slice(0,MAX_QUESTIONS).entries()){
  const raw=item as Record<string,unknown>;
  if(!raw||typeof raw!=='object')continue;
  const options=Array.isArray(raw.options)?raw.options.map(o=>String(o??'').slice(0,200)):[];
  const stem=String(raw.stem??raw.title??'').slice(0,MAX_STEM);
  const answer=normalizeAnswer(String(raw.answer??''),options.length);
  if(!stem||options.length<2||!answer)continue;
  questions.push({
   id:String(raw.id??('q'+(i+1))),
   type:answer.length>1||String(raw.type??'single')==='multi'?'multi':'single',
   stem,options,answer,
   chapter:String(raw.chapter??'未分类'),
   explanation:String(raw.explanation??raw.analysis??'').slice(0,1000)
  });
 }
 if(!questions.length)return {name:'',questions:[],error:'文件里没有解析出可用的题目（至少需要题干、2 个以上选项和答案）。'};
 return {name,questions,error:''};
}

// ── 界面 ──────────────────────────────────────────────────────────────
function current(state:State):Q|null{const i=state.order[state.index];return i===undefined?null:state.all[i]??null;}
function optionText(state:State,letter:string):string{
 const q=current(state);if(!q)return '';
 const idx=letter.charCodeAt(0)-65;return q.options[idx]??'';
}
function statLine(state:State):string{
 const q=current(state);if(!q)return '';
 const picked=state.answers[q.id]??'';
 const answered=Object.values(state.answers).filter(v=>v).length;
 return '第 '+(state.index+1)+' / '+state.order.length+' 题 · 已作答 '+answered+(picked?(' · 本题已选 '+picked):'');
}
function view(state:State,effects:UiEffect[]=[]):UiResult<State>{
 const children:UiNode[]=[];
 if(state.page==='library'){
  children.push({id:'title',type:'text',style:'title',text:'题库仓库'},
   {id:'status',type:'text',style:'caption',text:state.status},
   {id:'current',type:'text',text:'当前装载：'+state.bankName+'（'+state.all.length+' 题）'+(state.bankIsBuiltin?' · 内置':' · 导入')},
   {id:'import',type:'button',label:'导入 JSON 题库文件',event:'import',enabled:!state.pending},
   {id:'reset',type:'button',label:'恢复内置演示题库',event:'reset',enabled:!state.pending});
  if(state.importText)children.push({id:'importText',type:'text',style:'caption',text:state.importText});
  return {state,view:{id:'root',type:'scroll',padding:16,gap:12,children},effects};
 }
 if(state.page==='wrong'){
  children.push({id:'title',type:'text',style:'title',text:'错题本与收藏'},
   {id:'status',type:'text',style:'caption',text:state.status},
   {id:'wrongText',type:'text',text:state.wrongText||'还没有错题记录。'},
   {id:'favText',type:'text',style:'caption',text:'收藏：'+(state.favorites.length?state.favorites.length+' 题':'暂无')},
   {id:'clearWrong',type:'button',label:'清空错题记录',event:'clearWrong',enabled:!state.pending});
  return {state,view:{id:'root',type:'scroll',padding:16,gap:12,children},effects};
 }
 // ── main ──
 if(state.screen==='setup'){
  children.push({id:'title',type:'text',style:'title',text:'题库练习'},
   {id:'status',type:'text',style:'caption',text:state.status},
   {id:'bank',type:'text',text:'题库：'+state.bankName+'（'+state.all.length+' 题）'},
   {id:'chaptersLabel',type:'text',style:'label',text:'章节（不勾选 = 全部）'});
  state.chapters.forEach((chapter,i)=>children.push(
   {id:'ch.'+i,type:'toggle',label:chapter,value:state.picked.includes(chapter),event:'ch.'+i}));
  children.push({id:'mode',type:'select',label:'练习模式',value:state.mode,event:'mode',options:[
    {value:'order',label:'顺序练习'},{value:'shuffle',label:'乱序练习'},{value:'exam',label:'考试（交卷后统一判分）'},{value:'memorize',label:'背题模式'}]},
   {id:'autoNext',type:'toggle',label:'自动下一题（多选题与背题模式不跳）',value:state.autoNext,event:'autoNext'},
   // order 只在 start 时才填充，所以"能不能开始"要看装载到的题目数，不能看 order。
   {id:'start',type:'button',label:'开始练习',event:'start',enabled:state.loaded&&state.all.length>0},
   {id:'reload',type:'button',label:'重新装载题库',event:'reload',enabled:!state.pending});
  return {state,view:{id:'root',type:'scroll',padding:16,gap:12,children},effects};
 }
 if(state.screen==='practice'){
  const q=current(state);
  if(!q){children.push({id:'status',type:'text',text:'没有题目。'});}
  else{
   const picked=state.answers[q.id]??'';
   const judged=!!state.judged[q.id];
   children.push({id:'progress',type:'text',style:'caption',text:statLine(state)},
    {id:'chapter',type:'text',style:'caption',text:q.chapter+(q.type==='multi'?' · 多选题':' · 单选题')},
    {id:'stem',type:'text',style:'title',text:q.stem});
   q.options.forEach((opt,i)=>{
    const letter=String.fromCharCode(65+i),on=picked.includes(letter);
    let label=(on?'● ':'○ ')+letter+'. '+opt;
    if(judged&&q.answer.includes(letter))label+=' （正确选项）';
    children.push({id:'opt.'+letter,type:'button',label,event:'opt.'+letter});
   });
   children.push({id:'confirm',type:'button',label:'确认答案',event:'confirm',enabled:!!picked&&!judged&&state.mode!=='memorize'},
    {id:'fav',type:'button',label:state.favorites.includes(q.id)?'取消收藏':'收藏本题',event:'fav'});
   if(judged){
    children.push({id:'result',type:'text',text:state.rights[q.id]?'回答正确':'回答错误（正确答案 '+q.answer+'）'},
     {id:'explanation',type:'text',style:'caption',text:q.explanation||'本题没有解析。'});
   }
   children.push({id:'prev',type:'button',label:'上一题',event:'prev',enabled:state.index>0},
    {id:'next',type:'button',label:state.index>=state.order.length-1?'提交':'下一题',event:'next'},
    {id:'sheet',type:'button',label:'答题卡',event:'sheet'},
    {id:'autoNext',type:'toggle',label:'自动下一题',value:state.autoNext,event:'autoNext'},
    {id:'quit',type:'button',label:'结束本轮',event:'quit'});
  }
  return {state,view:{id:'root',type:'scroll',padding:16,gap:12,children},effects};
 }
 if(state.screen==='sheet'){
  children.push({id:'title',type:'text',style:'title',text:'答题卡'},
   {id:'legend',type:'text',style:'caption',text:'○ 未作答 · ● 已选未判 · √ 正确 · × 错误'});
  const rows:UiNode[]=[];
  for(let i=0;i<state.order.length;i+=6){
   const cells:UiNode[]=[];
   for(let j=i;j<Math.min(i+6,state.order.length);j++){
    const q=state.all[state.order[j]];const picked=state.answers[q.id]??'';
    const mark=state.judged[q.id]?(state.rights[q.id]?'√':'×'):(picked?'●':'○');
    cells.push({id:'cell.'+j,type:'button',label:(j+1)+' '+mark,event:'cell.'+j});
   }
   rows.push({id:'row.'+i,type:'row',gap:6,children:cells});
  }
  children.push(...rows,
   {id:'back',type:'button',label:'回到练习',event:'back'},
   {id:'submit',type:'button',label:'提交并查看总结',event:'submit'});
  return {state,view:{id:'root',type:'scroll',padding:16,gap:12,children},effects};
 }
 children.push({id:'title',type:'text',style:'title',text:'本次总结'},
  {id:'summary',type:'text',text:state.summary||'还没有提交。'},
  {id:'again',type:'button',label:'再来一轮',event:'again'},
  {id:'home',type:'button',label:'回到开始页',event:'home'});
 return {state,view:{id:'root',type:'scroll',padding:16,gap:12,children},effects};
}

// ── 判分与落库 ────────────────────────────────────────────────────────
function judge(state:State,qid:string){
 const q=state.all.find(item=>item.id===qid);
 if(!q)return state;
 const picked=state.answers[qid]??'';
 if(!picked||state.judged[qid])return state;
 const right=isRight(q,picked);
 const records={...state.records};
 const old=records[qid]??{wrong:0,right:0};
 records[qid]=right?{wrong:old.wrong,right:old.right+1}:{wrong:old.wrong+1,right:old.right};
 return {...state,judged:{...state.judged,[qid]:true},rights:{...state.rights,[qid]:right},records};
}
/** 交卷补判：把"选了但没点确认答案"的题一并判掉，未碰过的题留作未作答。 */
function judgePending(state:State):State{
 let next=state;
 for(const idx of state.order){const q=state.all[idx];if(q&&(state.answers[q.id]??''))next=judge(next,q.id);}
 return next;
}
function summaryOf(state:State):string{
 const total=state.order.length;
 let correct=0,wrong=0,unanswered=0;
 for(const idx of state.order){
  const q=state.all[idx];if(!q)continue;
  if(state.judged[q.id])(state.rights[q.id]?correct++:wrong++);else unanswered++;
 }
 const judged=correct+wrong;
 const percent=judged?Math.round(correct*100/judged):0;
 return '正确率 '+percent+'%（已判分 '+judged+' 题）· 答对 '+correct+' · 答错 '+wrong+' · 未作答 '+unanswered+' / 共 '+total+' 题\n'+
  encouragement(percent,judged,total);
}
function chaptersOf(all:Q[]):string[]{
 const seen:string[]=[];
 for(const q of all)if(!seen.includes(q.chapter))seen.push(q.chapter);
 return seen.sort((a,b)=>chapterKey(a)-chapterKey(b));
}
function fresh(page:'main'|'library'|'wrong',autoNext:boolean):State{
 return {serial:0,pending:'',status:'',page,loaded:false,screen:'setup',
  bankName:BUILTIN_NAME,bankIsBuiltin:true,all:BUILTIN,order:[],
  chapters:[],picked:[],mode:'order',autoNext,index:0,
  answers:{},judged:{},rights:{},favorites:[],records:{},summary:'',importText:'',wrongText:''};
}

const plugin:FeaturePlugin<State>={ui:{
 init:({pageId},context)=>{
  const page:(('main'|'library'|'wrong'))=(pageId==='library'||pageId==='wrong')?pageId:'main';
  const state=fresh(page,context.settings?.autoNext===true);
  state.pending='bank.'+(++state.serial);
  return view(state,[{id:state.pending,capability:'storage.get',version:1,input:{key:'bank'}}]);
 },
 reduce:({state,event})=>{
  const next={...state},effects:UiEffect[]=[];
  const send=(capability:string,input:Record<string,Json>,tag:string)=>{
   next.pending=tag+'.'+(++next.serial);
   effects.push({id:next.pending,capability,version:1,input});
  };
  const saveProgress=()=>send('storage.set',{key:'progress',value:{records:next.records,favorites:next.favorites}},'save');
  if(event.type==='effect.result'){
   if(event.effectId!==next.pending)return view(next);
   const tag=(event.effectId||'').split('.')[0];
   next.pending='';
   if(!event.result?.ok){next.status='读取失败：'+(event.result?.error.message||'未知原因');}
   else{
    const data=event.result.data as Record<string,unknown>;
    if(tag==='bank'){
     const bank=data as {name?:string;questions?:Q[]}|null;
     if(bank&&Array.isArray(bank.questions)&&bank.questions.length){
      next.all=bank.questions.slice(0,MAX_QUESTIONS);
      next.bankName=String(bank.name||'导入题库');next.bankIsBuiltin=false;
     }else{next.all=BUILTIN;next.bankName=BUILTIN_NAME;next.bankIsBuiltin=true;}
     next.chapters=chaptersOf(next.all);
     send('storage.get',{key:'progress'},'progress');
    }else if(tag==='progress'){
     const progress=(data??{}) as {records?:Records;favorites?:string[]};
     next.records=progress.records??{};
     next.favorites=progress.favorites??[];
     next.loaded=true;
     if(next.page==='wrong')next.wrongText=wrongBookText(next);
     next.status=next.page==='wrong'?'' :'已装载 '+next.bankName+'，共 '+next.all.length+' 题。';
    }else if(tag==='save')next.status='已保存到本机。';
    else if(tag==='pick')send('files.read',{handle:String(data.handle),length:MAX_FILE},'file');
    else if(tag==='file'){
     try{
      if(Number(data.size)>MAX_FILE||data.eof===false)throw new Error('文件过大，请把题库控制在 256 KiB 内');
      const parsed=parseBank(decodeBase64(String(data.base64||'')));
      if(parsed.error){next.status=parsed.error;}
      else{
       next.all=parsed.questions;next.bankName=parsed.name;next.bankIsBuiltin=false;
       next.chapters=chaptersOf(parsed.questions);
       next.importText='已导入 '+parsed.name+'：'+parsed.questions.length+' 题，'+next.chapters.length+' 个章节。';
       next.status=next.importText;
       send('storage.set',{key:'bank',value:{name:parsed.name,questions:parsed.questions}},'save');
      }
     }catch(error){next.status=(error as Error).message;}
    }
   }
  }else if(next.pending)return view(next);
  else if(event.name==='import')send('files.pick',{mimeTypes:['application/json','text/plain']},'pick');
  else if(event.name==='reset'){
   next.all=BUILTIN;next.bankName=BUILTIN_NAME;next.bankIsBuiltin=true;next.chapters=chaptersOf(BUILTIN);
   next.importText='';next.status='已恢复内置演示题库（'+BUILTIN.length+' 题）。';
   send('storage.set',{key:'bank',value:null},'save');
  }
  else if(event.name==='reload'){send('storage.get',{key:'bank'},'bank');next.status='正在重新装载…';}
  else if(event.name==='clearWrong'){next.records={};saveProgress();next.wrongText='还没有错题记录。';next.status='已清空错题记录。';}
  else if(event.name&&event.name.startsWith('ch.')){
   const chapter=next.chapters[Number(event.name.slice(3))];
   if(chapter)next.picked=next.picked.includes(chapter)?next.picked.filter(c=>c!==chapter):[...next.picked,chapter];
  }
  else if(event.name==='mode')next.mode=(['order','shuffle','exam','memorize'].includes(String(event.value))?String(event.value):'order')as Mode;
  else if(event.name==='autoNext')next.autoNext=!state.autoNext;
  else if(event.name==='start'){
   const pool=next.picked.length?next.all.filter(q=>next.picked.includes(q.chapter)):next.all;
   if(!pool.length){next.status='当前章节下没有题目。';}
   else{
    next.all=pool;next.order=buildOrder(pool,next.mode);
    next.index=0;next.answers={};next.judged={};next.rights={};next.summary='';
    next.screen='practice';
    next.status=next.mode==='exam'?'考试模式：交卷前不显示对错与解析。':'已开始，共 '+next.order.length+' 题。';
   }
  }
  else if(event.name&&event.name.startsWith('opt.')){
   const q=current(next);
   if(q&&next.mode!=='memorize'&&!next.judged[q.id]){
    const letter=event.name.slice(4),picked=next.answers[q.id]??'';
    let value:string;
    if(q.type==='multi')value=picked.includes(letter)?picked.split('').filter(c=>c!==letter).join(''):normalizeAnswer(picked+letter,q.options.length);
    else value=letter;
    next.answers={...next.answers,[q.id]:value};
    // 自动下一题：多选题与背题模式不跳（无从判断"选完了没有"）。
    if(next.autoNext&&q.type==='single'&&next.index<next.order.length-1)next.index++;
   }
  }
  else if(event.name==='confirm'){
   const q=current(next);
   if(q&&(next.answers[q.id]??'')&&!next.judged[q.id]&&next.mode!=='memorize'){
    Object.assign(next,judge(next,q.id));saveProgress();
   }
  }
  else if(event.name==='fav'){
   const q=current(next);
   if(q){next.favorites=next.favorites.includes(q.id)?next.favorites.filter(id=>id!==q.id):[...next.favorites,q.id];saveProgress();}
  }
  else if(event.name==='prev'&&next.index>0)next.index--;
  else if(event.name==='next'){
   if(next.index<next.order.length-1)next.index++;
   else{Object.assign(next,judgePending(next));next.summary=summaryOf(next);next.screen='summary';saveProgress();}
  }
  else if(event.name==='submit'){
   Object.assign(next,judgePending(next));next.summary=summaryOf(next);next.screen='summary';saveProgress();
  }
  else if(event.name==='sheet')next.screen='sheet';
  else if(event.name==='back')next.screen='practice';
  else if(event.name&&event.name.startsWith('cell.')){next.index=Number(event.name.slice(5));next.screen='practice';}
  else if(event.name==='quit'){Object.assign(next,judgePending(next));next.summary=summaryOf(next);next.screen='summary';saveProgress();}
  else if(event.name==='again'){
   next.index=0;next.answers={};next.judged={};next.rights={};next.summary='';next.screen='practice';
  }
  else if(event.name==='home'){
   next.screen='setup';next.all=next.bankIsBuiltin?BUILTIN:next.all;next.chapters=chaptersOf(next.all);next.order=[];next.picked=[];
  }
  return view(next,effects);
 }
}};

function wrongBookText(state:State):string{
 const items=state.all.filter(q=>(state.records[q.id]?.wrong??0)>0)
  .sort((a,b)=>(state.records[b.id]?.wrong??0)-(state.records[a.id]?.wrong??0));
 if(!items.length)return '还没有错题记录。';
 return '错题 '+items.length+' 道：\n'+items.slice(0,50).map(q=>
  '· ['+q.chapter+'] '+q.stem.slice(0,40)+(q.stem.length>40?'…':'')+'（错 '+(state.records[q.id]?.wrong??0)+' 次，对 '+(state.records[q.id]?.right??0)+' 次）').join('\n');
}
export default plugin;
