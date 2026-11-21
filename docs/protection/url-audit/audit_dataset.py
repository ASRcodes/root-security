from pathlib import Path
import csv,json,sys
from collections import Counter,defaultdict
from urllib.parse import urlsplit
ROOT=Path(r'C:\Users\rajsi\OneDrive\Desktop\suraksha-kavach\ml')
sys.path.insert(0,str(ROOT))
from features import FEATURE_NAMES
counts=defaultdict(Counter)
with (ROOT/'data/PhiUSIIL_Phishing_URL_Dataset.csv').open(encoding='utf-8-sig',newline='') as f:
 for r in csv.DictReader(f):
  label='legitimate' if int(r['label'])==1 else 'phishing'
  raw=r['URL'].strip();p=urlsplit(raw if '://' in raw else 'https://'+raw)
  c=counts[label];c['total']+=1;c['empty_path']+=not p.path;c['slash_only_path']+=p.path=='/';c['longer_path']+=bool(p.path and p.path!='/');c['has_query']+=bool(p.query);c['https']+=p.scheme.lower()=='https'
assets=Path(r'C:\Users\rajsi\AndroidStudioProjects\DontMissMOM\protection\src\main\assets')
u=json.loads((assets/'url_model.json').read_text(encoding='utf8'))
roots=Counter(FEATURE_NAMES[t['feature'][0]] for t in u['trees'])
allnodes=Counter(FEATURE_NAMES[j] for t in u['trees'] for j in t['feature'] if j>=0)
result={'raw_phiusiil_distribution':dict(counts),'training_code_tranco':'40,000 benign proxy URLs constructed as https:// + domain without trailing slash','url_model_root_split_features':dict(roots),'url_model_split_counts':dict(allnodes)}
(Path(__file__).parent/'dataset-structure-audit.json').write_text(json.dumps(result,indent=2),encoding='utf8')
print(json.dumps(result,indent=2))
