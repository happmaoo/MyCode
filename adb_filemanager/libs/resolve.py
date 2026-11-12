import urllib.request, xml.etree.ElementTree as ET, os, zipfile, shutil
M="https://dl.google.com/dl/android/maven2"
NS={"m":"http://maven.apache.org/POM/4.0.0"}
seen={}
def fetch(url):
    with urllib.request.urlopen(url, timeout=30) as r: return r.read()
def pom(g,a,v):
    return fetch(f"{M}/{g.replace('.','/')}/{a}/{v}/{a}-{v}.pom")
def resolve(g,a,v):
    key=(g,a)
    if key in seen: return
    seen[key]=v
    try: root=ET.fromstring(pom(g,a,v))
    except Exception as e:
        print("POM FAIL",g,a,v,e); return
    pack=root.findtext("m:packaging","jar",NS)
    # download artifact
    ext="aar" if pack=="aar" else "jar"
    data=fetch(f"{M}/{g.replace('.','/')}/{a}/{v}/{a}-{v}.{ext}")
    fn=f"aar/{a}-{v}.{ext}" if ext=="aar" else f"jar/{a}-{v}.jar"
    open(fn,"wb").write(data)
    if ext=="aar":
        z=zipfile.ZipFile(fn)
        if "classes.jar" in z.namelist():
            open(f"jar/{a}-{v}.jar","wb").write(z.read("classes.jar"))
        if "res/" in "".join(z.namelist()) and any(n.startswith("res/") for n in z.namelist()):
            d=f"res/{a}-{v}"; os.makedirs(d,exist_ok=True)
            for n in z.namelist():
                if n.startswith("res/") and not n.endswith("/"):
                    p=os.path.join(d,n[4:]); os.makedirs(os.path.dirname(p),exist_ok=True)
                    open(p,"wb").write(z.read(n))
    for dep in root.findall(".//m:dependencies/m:dependency",NS):
        dg=dep.findtext("m:groupId","",NS); da=dep.findtext("m:artifactId","",NS); dv=dep.findtext("m:version","",NS)
        scope=dep.findtext("m:scope","compile",NS)
        if scope in ("compile","runtime") and dv:
            resolve(dg,da,dv)
resolve("androidx.recyclerview","recyclerview","1.1.0")
print("CLOSURE:")
for (g,a),v in sorted(seen.items()): print(f"  {g}:{a}:{v}")
