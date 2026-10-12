import sys,pathlib,shutil,json,hashlib
root=pathlib.Path(__file__).resolve().parents[2]
out=pathlib.Path(__file__).resolve().parent/sys.argv[1]
out.mkdir(exist_ok=False)
shutil.copytree(root/'services/api/target/classes',out/'classes')
hashes={}
for base in ['services/api/src/main/java/com/promptoptimizer','apps/web/src']:
 for file in (root/base).rglob('*'):
  if not file.is_file():continue
  relative=file.relative_to(root);dest=out/'source'/relative
  dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,dest)
  hashes[str(relative)]=hashlib.sha256(file.read_bytes()).hexdigest()
(out/'source-hashes.json').write_text(json.dumps(hashes,indent=2),encoding='utf-8')
print('Candidate source and compiled classes frozen: '+out.name)
