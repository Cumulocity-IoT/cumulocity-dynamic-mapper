## Usefull commands based on  c8y go cli

Deactivate a mapping in the inventory 
`c8y inventory get --id 2612703 --raw | c8y inventory update --template "_.SelectMerge(input.value, {'d11r_mapping':{'active':false}})"`