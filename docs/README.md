# Documents

Every document has one folder, and a plan's folder is its state. To know whether a feature is built, check which folder its
plan is in. Create a folder when its first document arrives, and update this index in the same commit that moves a plan.

| Folder | What is in it |
|---|---|
| guide/ | Read before working. Design records of systems that are built and stay true. |
| [record/](record) | The live record of what is wrong. |
| plans/planned/ | Decided or drafted, nothing built yet. |
| plans/in-progress/ | Partly built. The plan's own status line says which step is next. |
| plans/done/ | Every step built, or closed by decision. Kept as the record of why. |
| plans/retired/ | Built and then removed, or abandoned. Kept for the reasoning. |

## record

| Document | What it is |
|---|---|
| [KNOWN_ISSUES.md](record/KNOWN_ISSUES.md) | Defects and debt that are owed but not planned. |
