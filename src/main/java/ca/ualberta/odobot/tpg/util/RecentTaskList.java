package ca.ualberta.odobot.tpg.util;

import java.util.ArrayList;
import java.util.HashSet;

public class RecentTaskList {
	
	ArrayList<String> holder = new ArrayList<String>();
	int desiredNumberOfElements = 0;
	
	//adds n of the same thing to the arraylist.
	public RecentTaskList(int desiredNumberOfElements)
	{
		this.desiredNumberOfElements = desiredNumberOfElements;
		
	}
	
	//this should really only be performed once, it adds n 
	//of the same thing.
	public void initializeTaskList(String startingTask)
	{
		for (int i = 0; i < desiredNumberOfElements; i++)
		{
			holder.add(startingTask);
		}
	}
	
	//jk the newest task has to be at [0] 
	//otherwise this won't work. sorry Rob and sorry efficiency.
	public void update(String newTask)
	{
		if (holder.size() == 0)
		{
			initializeTaskList(newTask);
		}
		else
		{
			holder.remove(holder.size()-1);
			holder.add(0, newTask);
		}
	
	}
	
	public String getAtIndex(int i)
	{
		return holder.get(i);
	}
	
	public ArrayList<String> getList()
	{
		return holder;
	}
	
	public HashSet<String> asHashSet()
	{
		HashSet<String> h = new HashSet<String>();
		for (String s : holder)
		{
			h.add(s);
		}
		
		return h;
	}

}
