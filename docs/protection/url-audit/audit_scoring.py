from pathlib import Path
import json, sys, hashlib

SOURCE=Path(r'C:\Users\rajsi\OneDrive\Desktop\suraksha-kavach\ml')
ASSETS=Path(r'C:\Users\rajsi\AndroidStudioProjects\DontMissMOM\protection\src\main\assets')
OUT=Path(__file__).parent
sys.path.insert(0,str(SOURCE))
from features import PublicSuffixList,parse_url,url_features,heuristic_score,normalize_text,FEATURE_NAMES
from portable import url_score,text_score
um=json.loads((ASSETS/'url_model.json').read_text(encoding='utf8'))
tm=json.loads((ASSETS/'text_model.json').read_text(encoding='utf8'))
psl=PublicSuffixList((ASSETS/'public_suffix_list.dat').read_text(encoding='utf8'))
def domain_lines(name):
 return {x.split('#',1)[0].strip().lower() for x in (ASSETS/name).read_text(encoding='utf8').splitlines() if x.split('#',1)[0].strip()}
allow=domain_lines('top_domains.txt'); block=domain_lines('blocklist.txt')
def is_blocked(host):
 return any('.'.join(host.split('.')[i:]) in block for i in range(len(host.split('.'))))

cases=[]
def add(category,url,message=None):cases.append((category,url,message or url))
for u in [
 'https://www.airtel.in/','https://airtel.in/','https://www.airtel.in','http://www.airtel.in/',
 'https://www.airtel.in/prepaid-recharge/','https://www.airtel.in/broadband/',
 'https://www.airtel.in/thanks-app/','https://www.airtel.in/help/','https://www.airtel.in/login',
 'https://www.airtel.in/prepaid-recharge/?utm_source=whatsapp&utm_campaign=november2026',
 'https://www.goodmonk.in/','https://goodmonk.in/','https://www.goodmonk.in','http://www.goodmonk.in/',
 'https://goodmonk.in/products/good-monk-family-nutrition-mix',
 'https://www.goodmonk.in/collections/all','https://www.goodmonk.in/account/login',
 'https://www.goodmonk.in/?utm_source=whatsapp&utm_medium=share&utm_campaign=family_nutrition',
 'https://www.google.com/','https://en.wikipedia.org/wiki/Android_(operating_system)',
 'https://www.amazon.in/dp/B0D1234567?ref=share','https://www.flipkart.com/',
 'https://www.jio.com/','https://www.zomato.com/','https://www.swiggy.com/',
 'https://www.zerodha.com/','https://www.npci.org.in/','https://www.sbi.co.in/',
 'https://www.myntra.com/','https://www.ajio.com/','https://www.decathlon.in/',
 'https://www.bbc.co.uk/news','https://developer.android.com/studio/run/device',
 'https://www.microsoft.com/en-us/security','https://www.wikipedia.org/',
 'https://docs.python.org/3/library/urllib.parse.html','https://scikit-learn.org/stable/',
 'https://www.goodmonk.com/','https://www.airtel.com/',
]:add('benign_candidate_not_independently_verified',u)
for u in ['https://example.org/login','https://example.org/products/12345678','https://example.org/?utm_source=whatsapp&ref=1234567890',
 'https://bit.ly/abc123','https://tinyurl.com/family-meetup','http://192.168.1.1/','https://xn--bcher-kva.example/']:
 add('neutral_structure_not_evidence_of_malice',u)
for u in ['https://paypal.com.verify-account.example/login?otp=123456','https://sbi.verify-kyc.example/account-update',
 'https://paypa1.example/verify','https://secure--bank--verify--account.example/login',
 'https://trusted.example@evil.example/verify','https://scam-demo.example/claim']:
 add('inert_suspicious_structure',u)
for msg in ['Check out this product: https://www.goodmonk.in/',
 'I found this on Good Monk: https://www.goodmonk.in/',
 'Your Airtel recharge is successful. View details at https://www.airtel.in/',
 'Recharge now and get extra data with Airtel https://www.airtel.in/',
 'Get 20% off on your first order. Shop now https://www.goodmonk.in/',
 'Your account will be blocked. Share your OTP immediately to verify your account https://www.airtel.in/',
 'Hi Mom, here is the link you asked for https://www.goodmonk.in/',
 'Please click this link https://www.goodmonk.in/']:
 url=msg.split()[-1]; add('message_context',url,msg)

rows=[]
for category,u,msg in cases:
 pu=parse_url(u)
 assert pu
 f=url_features(pu,psl);us=url_score(um,f);ts=text_score(tm,msg)
 hs,forced,reasons=heuristic_score(pu,psl)
 reg=psl.registrable(pu.host)
 al=reg in allow and not pu.user_info and not any(x.startswith('xn--') for x in pu.host.split('.'))
 bl=is_blocked(pu.host)
 level='SAFE'; decision='model_and_rules'
 if al:
  decision='allowlist_plus_text'
  level='DANGEROUS' if ts>=tm['dangerous_threshold'] else 'SUSPICIOUS' if ts>=tm['suspicious_threshold'] else 'SAFE'
 elif bl:level='DANGEROUS';decision='blocklist'
 elif hs>=.75 or us>=um['dangerous_threshold'] or ts>=tm['dangerous_threshold']:level='DANGEROUS'
 elif forced or hs>=.45 or us>=um['suspicious_threshold'] or ts>=tm['suspicious_threshold']:level='SUSPICIOUS'
 rows.append(dict(category=category,url=u,message=msg,normalized_text=normalize_text(msg),registered_domain=reg,url_model_score=us,text_model_score=ts,
  heuristic_score=hs,forced_suspicious=forced,heuristic_reasons=sorted(reasons),allowlisted=al,blocklisted=bl,level=level,decision_path=decision,
  features=dict(zip(FEATURE_NAMES,map(float,f)))))
report={'method':'Pure offline reference scorer with exact currently deployed JSON/assets. URL strings are never fetched. Kotlin/Python parity is covered by project tests. Candidate domains and test paths are not independently verified to exist.',
 'assets':{n:hashlib.sha256((ASSETS/n).read_bytes()).hexdigest() for n in ['url_model.json','text_model.json','top_domains.txt','blocklist.txt','public_suffix_list.dat']},
 'thresholds':{'url':[um['suspicious_threshold'],um['dangerous_threshold']],'text':[tm['suspicious_threshold'],tm['dangerous_threshold']]},'rows':rows}
(OUT/'baseline-scores.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf8')
for r in rows:print(f"{r['level']:11} URL={r['url_model_score']:.4f} TEXT={r['text_model_score']:.4f} RULE={r['heuristic_score']:.2f} {r['decision_path']:20} {r['message']}")
print('TOTAL',len(rows),'non-safe',sum(r['level']!='SAFE' for r in rows))
