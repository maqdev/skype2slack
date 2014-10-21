#!/bin/bash

function show_help {
  echo "Usage: esky.sh -f path-to-skype-db -m [-s start-id] [-o chat-id] OR -c"
  echo "where -m extracts messages and -c extracts chats"
}

OPTIND=1

db_file=""
chats=0
messages=0
start=0
convo=""

while getopts "h?cmo:s:f:" opt; do
    case "$opt" in
    h|\?)
        show_help
        exit 0
        ;;
    c)  chats=1
        ;;
    m)  messages=1
        ;;
    s)  start=$OPTARG
        ;;
    o)  convo=$OPTARG
        ;;
    f)  db_file=$OPTARG
        ;;
    esac
done

shift $((OPTIND-1))

[ "$1" = "--" ] && shift

if [ "$db_file" = "" ]; then
  show_help
  exit 1
fi

# echo db_file = $db_file chats = $chats messages = $messages start = $start

if [ "$messages" -eq "1" ]; then
  qry="select id, convo_id, author, from_dispname, timestamp, body_xml from Messages where id > $start"
  if [ "$convo" != "" ]; then
    qry="$qry and convo_id=$convo;"
  fi
  qry="$qry;"
  #echo $qry;
  ./sqlite3 -html $db_file "$qry"
elif [ "$chats" -eq "1" ]; then
  ./sqlite3 -html $db_file "select id,DisplayName from Conversations"
else
  show_help
  exit 1
fi
