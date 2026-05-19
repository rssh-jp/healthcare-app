#!/bin/bash
cd /mnt/c/Users/tarau/home/prj/github/healthcare-app
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  STATUS=$(gh run list --limit 1 --json status -q '.[0].status')
  if [ "$STATUS" != "in_progress" ] && [ "$STATUS" != "queued" ]; then
    echo "Completed with status: $STATUS"
    break
  fi
  echo "Still running... ($i/20)"
  sleep 15
done
gh run list --limit 1 --json databaseId,status,conclusion -q '.[0]'
