from pathlib import Path
import json, sys

ROOT=Path(sys.argv[1]).resolve()
rows=[json.loads(line) for line in (ROOT/'results.jsonl').read_text(encoding='utf-8').splitlines()]
by={row['tag']:row for row in rows}
assert len(by)==len(rows) and not any('error' in row for row in rows)
failures=[]
checks=0
def check(condition,message):
    global checks
    checks+=1
    if not condition: failures.append(message)

for tag in ['initial','idle0-loaded','idle1-loaded','idle2-loaded','reload-idle','active-reloaded','final']:
    row=by[tag]
    check(row['present'] and row['enabled'] and row['listeners']==18,tag+' functional registration')
    members=row['nightcoreChildren']
    check(len(members)==1 and members[0]['instance']==row['instance'] and members[0]['enabled'],tag+' registry refers to current instance')
for tag in ['idle0-unloaded','idle1-unloaded','idle2-unloaded','active-unloaded']:
    check(not by[tag]['present'],tag+' plugin-manager removal')
    check(by[tag]['nightcoreChildren']==[],tag+' unregisters the retired instance')
for tag in ['seed7','idle0-data','idle1-data','idle2-data','reload-idle-data','active-reloaded-data']:
    check(by[tag]['keys']==7 and by[tag]['owner'],tag+' persisted keys')
for tag in ['initial-reward','idle0-reward','idle1-reward','idle2-reward','active-reloaded-reward']:
    check(by[tag]['inventory']=={'DIAMOND':1},tag+' single reward')
active=by['active-inventory']
check(active.get('openingShifts',active['shiftEvents'])>0 and active.get('openingAllowed',active['allowed'])==0 and active['viewType']=='CRAFTING' and active['inventory']=={},'active unload returns to an empty closed window')
gc=by['final-gc']
check(gc['oldPluginsAlive']==0,'all retired ExcellentCrates instances released')
check(gc['oldLoadersAlive']==0,'all retired ExcellentCrates classloaders released')
check(by['fixture-reload']['registeredBefore'] and by['fixture-reload']['registeredAfter'],'ordinary configuration reload keeps registration')
for tag in ['fixture-normal','fixture-unload-failure','fixture-shutdown-failure']:
    row=by[tag]
    check(row['registeredBefore'] and not row['registeredAfter'] and not row['enabled'],tag+' always unregisters')
check(by['fixture-normal']['shutdownCalls']==1,'normal disable reaches shutdown hook')
check(by['fixture-unload-failure']['shutdownCalls']==0,'unload failure preserves the original hook order')
check(by['fixture-shutdown-failure']['shutdownCalls']==1,'shutdown failure reaches the hook before unregister')
if 'help' in by:
    for name in ['textMatches','hiddenWhenDenied','visibleWhenAllowed','realRoutePreserved','hiddenAfterUnregister','visibleAfterRegister','topicIdentityPreserved','amendedTextPreserved']:
        check(by['help'][name],'help '+name)
    check(by['help-routes']['invocations']==3,'unqualified, alias and namespaced commands route to the real executor')
if 'help-factory' in by:
    for name in ['nativeFactoryNotPreempted','customFactorySelected','customTopicPreserved']:
        check(by['help-factory'][name],'help factory '+name)
if 'active-before-unload' in by:
    before=by['active-before-unload']
    check(before['openingPresent'] and before['openingRunning'] and not before['openingCompleted'],'unload starts while the actual opening is running')
status=json.loads((ROOT/'run-status.json').read_text(encoding='utf-8'))
check(status['serverExit']==0 and status['clientStopped'],'normal server exit')
report={'checks':checks,'passed':checks-len(failures),'failed':len(failures),'failures':failures,
        'retiredPluginSamples':5,'finalOldPluginsAlive':gc['oldPluginsAlive'],'finalOldLoadersAlive':gc['oldLoadersAlive'],
        'activeShiftEvents':active['shiftEvents'],'activeAllowedShiftEvents':active['allowed'],
        'openingShiftEvents':active.get('openingShifts'),'openingAllowedShiftEvents':active.get('openingAllowed'),
        'nativeWindowShiftEvents':active.get('nativeWindowShifts')}
(ROOT/'validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
raise SystemExit(1 if failures else 0)
