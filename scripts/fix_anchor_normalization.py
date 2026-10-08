from pathlib import Path

path = Path('app/src/main/java/io/github/alagga/gonesmart/GmmpQueueMutationBridge.kt')
text = path.read_text()

def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'GmmpQueueMutationBridge.kt: expected one replacement, found {count}')
    text = text.replace(old, new, 1)

replace_once(
    '''    /**
     * Rebase a Track-Auto-DJ-owned sparse queue to 1..N after GMMP's native
     * refill. GMMP 4.2.0 exposed a verified append allocator; 4.2.1 does not.
     * Do not guess its obfuscated replacement. Instead update only the proven
     * native Queue entities, move the already-passively-verified playback
     * pointer if required, and require an independent Cursor postcondition.
     */
''',
    '''    /**
     * Compact a Track-Auto-DJ-owned sparse queue around its already verified
     * absolute Current position after GMMP's native refill. GMMP 4.2.0 exposed
     * a verified append allocator; 4.2.1 does not. Do not guess its obfuscated
     * replacement and do not move Current merely to make the queue start at 1.
     * Update only the proven native Queue entities and require an independent
     * Cursor postcondition.
     */
'''
)
replace_once(
    '''            ordered.forEachIndexed { index, row ->
                setInt(resolved.position, row, index + 1)
            }
''',
    '''            ordered.forEachIndexed { index, row ->
                setInt(
                    resolved.position,
                    row,
                    plan.normalizedPositions[index]
                )
            }
'''
)
replace_once(
    '''                    plan.originalPositions.joinToString(",") +
                    " | new=1.." + plan.normalizedPositions.size +
                    " | currentId=" + plan.currentQueueId +
''',
    '''                    plan.originalPositions.joinToString(",") +
                    " | new=" + plan.normalizedPositions.joinToString(",") +
                    " | currentId=" + plan.currentQueueId +
'''
)
if 'setInt(resolved.position, row, index + 1)' in text:
    raise SystemExit('stale absolute-1 normalization writer still present')
path.write_text(text)

# Keep the controller comment aligned with the new 4.2.1 ownership boundary.
controller = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt')
ctext = controller.read_text()
old = '''    // Hard barrier around native Play -> seed isolation -> initial native fill.
    // It is activated synchronously before Play so neither an old-queue refill
    // nor GMMP's transitional upcoming-count refill can race Track Mix.
'''
new = '''    // Mutation barrier around seed isolation -> initial native fill. It is
    // armed before native Play, but 4.2.1 WAIT_PLAY continuity refills pass
    // through natively until GoneSmart actually owns CLEARING/FILLING.
'''
if ctext.count(old) != 1:
    raise SystemExit('TrackMixController.kt: ownership comment anchor mismatch')
controller.write_text(ctext.replace(old, new, 1))

print('Anchored queue normalization verified and repaired.')
